package dev.flashclips;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.FlashbackChunkMeta;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import com.moulberry.flashback.record.ReplayMarker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns the tail of the running Flashback recording into standalone Flashback replay files.
 *
 * Flow: request -> (wait for kill delay) -> force Flashback to close the current chunk ->
 * on Flashback's writer thread, hard-link the newest chunks + chunk cache into a staging folder ->
 * on our own IO thread, zip them with rewritten metadata into clips/.
 */
public final class ClipManager {
    public enum Type { MANUAL, KILL }

    private record Request(Type type, long dueTick, UUID victim, String victimName, int killRecordingTick,
                           LocalDateTime time) {}

    private record Selection(List<String> names, List<int[]> chunkInfo, int startTick, int lengthTicks) {}

    private record Job(Request request, Selection selection, JsonObject metaJson) {}

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlashClips-IO");
        t.setDaemon(true);
        return t;
    });

    private static final List<Request> scheduled = new ArrayList<>();
    private static final List<Request> awaitingSplit = new ArrayList<>();
    public static volatile boolean forceSplit = false;

    private static Recorder trimRecorder;
    private static int trimmedUpTo;

    private ClipManager() {}

    public static void reset() {
        scheduled.clear();
        awaitingSplit.clear();
        forceSplit = false;
    }

    // ---------------------------------------------------------------- requests (main thread)

    public static void requestManualClip() {
        if (Flashback.RECORDER == null) {
            FlashClips.message(Component.literal("Nothing to clip: no Flashback recording is running."), ChatFormatting.RED);
            return;
        }
        scheduled.add(new Request(Type.MANUAL, FlashClips.ticks(), null, null, -1, LocalDateTime.now()));
        FlashClips.message(Component.literal("Saving clip..."), ChatFormatting.GRAY);
    }

    public static void requestKillClip(UUID victim, String victimName) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null) return;
        int recTick = ((RecorderExt) recorder).flashclips$getWrittenTicks();
        long due = FlashClips.ticks() + Math.max(0, FlashClips.CONFIG.killClipDelaySeconds) * 20L;
        scheduled.add(new Request(Type.KILL, due, victim, victimName, recTick, LocalDateTime.now()));
    }

    public static void tick() {
        long now = FlashClips.ticks();
        scheduled.removeIf(r -> {
            if (r.dueTick() <= now) {
                awaitingSplit.add(r);
                return true;
            }
            return false;
        });
        if (!awaitingSplit.isEmpty()) {
            if (Flashback.RECORDER == null) {
                awaitingSplit.clear();
                FlashClips.message(Component.literal("Clip dropped: the Flashback recording stopped."), ChatFormatting.RED);
            } else {
                forceSplit = true;
            }
        }
    }

    /** Called at the start of Flashback.finishRecordingReplay. Returns true to cancel Flashback's own finish. */
    public static boolean beforeFlashbackFinish() {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null) return false;

        // Kill clips still waiting for their delay get saved now, with whatever there is.
        awaitingSplit.addAll(scheduled);
        scheduled.clear();
        boolean pending = !awaitingSplit.isEmpty();

        boolean owned = recorder == FlashClips.ownedRecorder;
        if (!owned || !FlashClips.CONFIG.discardBufferOnLeave) {
            // Flashback itself calls endTick(close=true) next, which closes the chunk and triggers our clips.
            if (pending) forceSplit = true;
            return false;
        }

        try {
            if (pending) {
                forceSplit = true;
                recorder.endTickWithContext(true);
            }
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Failed to flush pending clips", t);
        }
        FlashClips.ownedRecorder = null;
        Flashback.cancelRecordingReplay(); // waits for queued writer tasks (our staging) then deletes the buffer
        return true;
    }

    // ---------------------------------------------------------------- chunk finished (main thread, inside Recorder.endTick)

    public static void onChunkCompleted(Recorder recorder) {
        RecorderExt ext = (RecorderExt) recorder;
        FlashbackMeta meta = ext.flashclips$getMetadata();
        Path recordFolder = ext.flashclips$getRecordFolder();

        if (!awaitingSplit.isEmpty()) {
            List<Request> requests = new ArrayList<>(awaitingSplit);
            awaitingSplit.clear();
            forceSplit = false;

            Selection selection = select(meta, FlashClips.CONFIG.bufferTicks());
            JsonObject metaJson = meta.toJson();
            for (Request request : requests) {
                Job job = new Job(request, selection, metaJson.deepCopy());
                recorder.submitCustomTask(writer -> stage(job, recordFolder));
            }
        }

        if (recorder == FlashClips.ownedRecorder && FlashClips.CONFIG.trimOldChunks && FlashClips.CONFIG.discardBufferOnLeave) {
            if (trimRecorder != recorder) {
                trimRecorder = recorder;
                trimmedUpTo = 0;
            }
            Selection keep = select(meta, FlashClips.CONFIG.bufferTicks() + FlashClips.CONFIG.chunkTicks());
            List<String> all = new ArrayList<>(meta.chunks.keySet());
            int firstKept = all.size() - keep.names().size();
            if (firstKept > trimmedUpTo) {
                List<String> delete = new ArrayList<>(all.subList(trimmedUpTo, firstKept));
                trimmedUpTo = firstKept;
                recorder.submitCustomTask(writer -> {
                    for (String name : delete) {
                        try {
                            Files.deleteIfExists(recordFolder.resolve(name));
                        } catch (Throwable t) {
                            FlashClips.LOGGER.warn("Couldn't delete old buffer chunk {}", name, t);
                        }
                    }
                });
            }
        }
    }

    /** Newest chunks that together cover at least wantTicks. */
    private static Selection select(FlashbackMeta meta, int wantTicks) {
        List<Map.Entry<String, FlashbackChunkMeta>> entries = new ArrayList<>(meta.chunks.entrySet());
        int total = 0;
        for (Map.Entry<String, FlashbackChunkMeta> e : entries) total += e.getValue().duration;

        int acc = 0;
        int i = entries.size();
        while (i > 0 && acc < wantTicks) {
            i--;
            acc += entries.get(i).getValue().duration;
        }
        List<String> names = new ArrayList<>();
        List<int[]> info = new ArrayList<>();
        for (int j = i; j < entries.size(); j++) {
            FlashbackChunkMeta cm = entries.get(j).getValue();
            names.add(entries.get(j).getKey());
            info.add(new int[]{cm.duration, cm.forcePlaySnapshot ? 1 : 0});
        }
        return new Selection(names, info, total - acc, acc);
    }

    // ---------------------------------------------------------------- staging (Flashback writer thread)

    private static void stage(Job job, Path recordFolder) {
        // Never throw here: an exception on Flashback's writer thread would break the recording.
        try {
            Path staging = FlashClips.clipsRoot().resolve(".staging").resolve(UUID.randomUUID().toString());
            Files.createDirectories(staging);

            for (String name : job.selection().names()) {
                linkOrCopy(recordFolder.resolve(name), staging.resolve(name));
            }

            Map<String, Long> cacheSizes = new LinkedHashMap<>();
            Path cacheDir = recordFolder.resolve("level_chunk_caches");
            if (Files.isDirectory(cacheDir)) {
                Path stagedCacheDir = staging.resolve("level_chunk_caches");
                Files.createDirectories(stagedCacheDir);
                try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir)) {
                    for (Path file : files) {
                        String fn = file.getFileName().toString();
                        // Remember the size now: the cache is append-only and keeps growing after this.
                        cacheSizes.put(fn, Files.size(file));
                        linkOrCopy(file, stagedCacheDir.resolve(fn));
                    }
                }
            }
            long legacyCacheSize = -1;
            Path legacyCache = recordFolder.resolve("level_chunk_cache");
            if (Files.exists(legacyCache)) {
                legacyCacheSize = Files.size(legacyCache);
                linkOrCopy(legacyCache, staging.resolve("level_chunk_cache"));
            }
            Path icon = recordFolder.resolve("icon.png");
            if (Files.exists(icon)) {
                Files.copy(icon, staging.resolve("icon.png"), StandardCopyOption.REPLACE_EXISTING);
            }

            long finalLegacyCacheSize = legacyCacheSize;
            IO.submit(() -> writeClip(job, staging, cacheSizes, finalLegacyCacheSize));
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Failed to stage clip", t);
            FlashClips.message(Component.literal("Failed to save clip: " + t), ChatFormatting.RED);
        }
    }

    private static void linkOrCopy(Path src, Path dst) throws IOException {
        Files.deleteIfExists(dst);
        try {
            Files.createLink(dst, src);
        } catch (Exception e) {
            Files.copy(src, dst);
        }
    }

    // ---------------------------------------------------------------- writing the zip (our IO thread)

    private static void writeClip(Job job, Path staging, Map<String, Long> cacheSizes, long legacyCacheSize) {
        Request req = job.request();
        Selection sel = job.selection();
        try {
            String stamp = FILE_TIME.format(req.time());
            String title = req.type() == Type.KILL ? "Kill " + req.victimName() + " " + stamp : "Clip " + stamp;

            FlashbackMeta meta = FlashbackMeta.fromJson(job.metaJson());
            meta.replayIdentifier = UUID.randomUUID();
            meta.name = title;

            LinkedHashMap<String, FlashbackChunkMeta> chunks = new LinkedHashMap<>();
            for (int i = 0; i < sel.names().size(); i++) {
                FlashbackChunkMeta cm = new FlashbackChunkMeta();
                cm.duration = sel.chunkInfo().get(i)[0];
                cm.forcePlaySnapshot = sel.chunkInfo().get(i)[1] != 0;
                chunks.put("c" + i + ".flashback", cm);
            }
            meta.chunks = chunks;
            meta.totalTicks = sel.lengthTicks();

            TreeMap<Integer, ReplayMarker> markers = new TreeMap<>();
            if (meta.replayMarkers != null) {
                for (Map.Entry<Integer, ReplayMarker> e : meta.replayMarkers.entrySet()) {
                    int t = e.getKey() - sel.startTick();
                    if (t >= 0 && t < sel.lengthTicks()) markers.put(t, e.getValue());
                }
            }
            if (req.type() == Type.KILL && sel.lengthTicks() > 0) {
                int t = Math.clamp(req.killRecordingTick() - sel.startTick(), 0, sel.lengthTicks() - 1);
                markers.put(t, new ReplayMarker(0xFF4040, null, "Killed " + req.victimName()));
            }
            meta.replayMarkers = markers;

            Path dir = targetDirectory(req);
            Files.createDirectories(dir);
            String baseName = req.type() == Type.KILL ? "kill_" + safe(req.victimName()) + "_" + stamp : "clip_" + stamp;
            Path out = dir.resolve(baseName + ".zip");
            for (int n = 2; Files.exists(out); n++) out = dir.resolve(baseName + "_" + n + ".zip");
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");

            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                zip.putNextEntry(new ZipEntry("metadata.json"));
                zip.write(GSON.toJson(meta.toJson()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                Path icon = staging.resolve("icon.png");
                if (Files.exists(icon)) {
                    zip.putNextEntry(new ZipEntry("icon.png"));
                    Files.copy(icon, zip);
                    zip.closeEntry();
                }
                for (Map.Entry<String, Long> e : cacheSizes.entrySet()) {
                    zip.putNextEntry(new ZipEntry("level_chunk_caches/" + e.getKey()));
                    copyLimited(staging.resolve("level_chunk_caches").resolve(e.getKey()), zip, e.getValue());
                    zip.closeEntry();
                }
                if (legacyCacheSize >= 0) {
                    zip.putNextEntry(new ZipEntry("level_chunk_cache"));
                    copyLimited(staging.resolve("level_chunk_cache"), zip, legacyCacheSize);
                    zip.closeEntry();
                }
                for (int i = 0; i < sel.names().size(); i++) {
                    zip.putNextEntry(new ZipEntry("c" + i + ".flashback"));
                    Files.copy(staging.resolve(sel.names().get(i)), zip);
                    zip.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);

            String rel = FabricLoader.getInstance().getGameDir().relativize(out).toString();
            FlashClips.message(Component.literal("Saved " + (sel.lengthTicks() / 20) + "s clip: " + rel), ChatFormatting.GREEN);
            FlashClips.LOGGER.info("Saved clip {}", out);
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Failed to write clip", t);
            FlashClips.message(Component.literal("Failed to save clip: " + t), ChatFormatting.RED);
        } finally {
            deleteRecursive(staging);
        }
    }

    /** clips/manual clips, or clips/kill clips/<uuid>_<latest name> (renamed when the player changes name). */
    private static Path targetDirectory(Request req) throws IOException {
        Path root = FlashClips.clipsRoot();
        if (req.type() == Type.MANUAL) return root.resolve("manual clips");

        Path killRoot = root.resolve("kill clips");
        Files.createDirectories(killRoot);
        if (req.victim() == null) return killRoot.resolve("unknown_" + safe(req.victimName()));

        String prefix = req.victim().toString();
        Path wanted = killRoot.resolve(prefix + "_" + safe(req.victimName()));
        if (Files.isDirectory(wanted)) return wanted;

        try (Stream<Path> list = Files.list(killRoot)) {
            Path existing = list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst().orElse(null);
            if (existing != null) {
                Files.move(existing, wanted);
                FlashClips.LOGGER.info("Renamed {} -> {} (username change)", existing.getFileName(), wanted.getFileName());
            }
        }
        return wanted;
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static void copyLimited(Path src, OutputStream out, long limit) throws IOException {
        try (InputStream in = Files.newInputStream(src)) {
            byte[] buf = new byte[1 << 16];
            long remaining = limit;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    private static void deleteRecursive(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
