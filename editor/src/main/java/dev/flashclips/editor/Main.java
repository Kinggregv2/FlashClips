package dev.flashclips.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Clips editor: a tiny local web app. Start it from the game folder (Clips Editor.bat); it serves a page on
 * 127.0.0.1 and opens it in the browser. Exports go to "montage clips/montages".
 */
public final class Main {
    private static Path clipsDir;
    private static Path outDir;
    private static Path tempDir;
    private static volatile Path music;
    private static volatile long lastPing = System.currentTimeMillis();

    private static volatile String state = "idle";
    private static volatile double progress = 0;
    private static volatile String message = "";
    private static volatile Path lastOutput;

    private record Cached(long modified, ClipInfo info) {}

    private static final Map<String, Cached> probeCache = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Path gameDir = (args.length > 0 ? Path.of(args[0]) : Path.of("")).toAbsolutePath();
        clipsDir = gameDir.resolve("montage clips");
        outDir = clipsDir.resolve("montages");
        Files.createDirectories(outDir);
        tempDir = Files.createTempDirectory("clips-editor");
        readModConfig(gameDir.resolve("config").resolve("clips.json"));

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", Main::index);
        server.createContext("/api/clips", Main::listClips);
        server.createContext("/clip", Main::serveClip);
        server.createContext("/api/music", Main::uploadMusic);
        server.createContext("/api/export", Main::startExport);
        server.createContext("/api/status", ex -> sendJson(ex, status()));
        server.createContext("/api/defaults", ex -> sendJson(ex, "{\"before\":" + Json.num(configBefore) + ",\"after\":" + Json.num(ClipInfo.defaultAfter) + "}"));
        server.createContext("/api/reveal", Main::reveal);
        server.createContext("/api/ping", ex -> {
            lastPing = System.currentTimeMillis();
            sendJson(ex, "{\"ok\":true}");
        });
        server.createContext("/api/quit", ex -> {
            sendJson(ex, "{\"ok\":true}");
            System.exit(0);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        log("Clips editor at " + url + " (clips: " + clipsDir + ")");
        if (!Boolean.getBoolean("clips.noBrowser")) openBrowser(url);

        // Quit on its own once the page has been closed for a while (and nothing is exporting).
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!"running".equals(state) && System.currentTimeMillis() - lastPing > 120_000) System.exit(0);
            }
        }, "watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Picks up the mod's before/after settings (shown as the editor defaults). */
    static volatile double configBefore = 1.0;

    private static void readModConfig(Path file) {
        try {
            String json = Files.readString(file);
            Matcher b = Pattern.compile("\"screenSecondsBefore\"\\s*:\\s*([0-9.]+)").matcher(json);
            Matcher a = Pattern.compile("\"screenSecondsAfter\"\\s*:\\s*([0-9.]+)").matcher(json);
            if (b.find()) configBefore = Double.parseDouble(b.group(1));
            if (a.find()) ClipInfo.defaultAfter = Double.parseDouble(a.group(1));
        } catch (Exception ignored) {
            // no config yet: keep defaults
        }
    }

    static void log(String s) {
        System.out.println("[Clips editor] " + s);
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            log("Open " + url + " in your browser");
        }
    }

    // ---------------------------------------------------------------- handlers

    private static void index(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "not found".getBytes());
            return;
        }
        try (InputStream in = Main.class.getResourceAsStream("/editor/index.html")) {
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void listClips(HttpExchange ex) throws IOException {
        List<String> json = new ArrayList<>();
        if (Files.isDirectory(clipsDir)) {
            List<Path> files;
            try (Stream<Path> list = Files.list(clipsDir)) {
                files = list.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".mp4") || n.endsWith(".mkv");
                        })
                        .sorted()
                        .toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                long modified = Files.getLastModifiedTime(f).toMillis();
                Cached c = probeCache.get(name);
                if (c == null || c.modified() != modified) {
                    ClipInfo info = ClipInfo.probe(f);
                    if (info == null) continue;
                    c = new Cached(modified, info);
                    probeCache.put(name, c);
                }
                json.add(c.info().toJson());
            }
        }
        sendJson(ex, "[" + String.join(",", json) + "]");
    }

    private static Path clipFile(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        Path p = clipsDir.resolve(name).normalize();
        return p.getParent().equals(clipsDir.normalize()) && Files.isRegularFile(p) ? p : null;
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private static void serveClip(HttpExchange ex) throws IOException {
        Path f = clipFile(query(ex).get("name"));
        if (f == null) {
            send(ex, 404, "text/plain", "not found".getBytes());
            return;
        }
        long len = Files.size(f);
        long start = 0, end = len - 1;
        int code = 200;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null) {
            Matcher m = RANGE.matcher(range);
            if (m.matches()) {
                if (!m.group(1).isEmpty()) start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) end = Math.min(len - 1, Long.parseLong(m.group(2)));
                if (m.group(1).isEmpty() && !m.group(2).isEmpty()) {
                    start = Math.max(0, len - Long.parseLong(m.group(2)));
                    end = len - 1;
                }
                code = 206;
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + len);
            }
        }
        String type = f.toString().toLowerCase(Locale.ROOT).endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        long count = end - start + 1;
        ex.sendResponseHeaders(code, count);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r"); OutputStream out = ex.getResponseBody()) {
            raf.seek(start);
            byte[] buf = new byte[1 << 16];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException ignored) {
            // browser cancelled the request (normal while seeking)
        }
    }

    private static void uploadMusic(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", new byte[0]);
            return;
        }
        String name = query(ex).getOrDefault("name", "music.mp3");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]", "") : ".mp3";
        Path target = tempDir.resolve("music" + ext);
        try (InputStream in = ex.getRequestBody()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        music = target;
        sendJson(ex, "{\"ok\":true}");
    }

    /**
     * Body: first line is url-encoded settings (musicVolume, gameVolume, musicStart, useMusic),
     * then one line per clip: name TAB start TAB end (seconds).
     */
    private static void startExport(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", new byte[0]);
            return;
        }
        if ("running".equals(state)) {
            sendJson(ex, "{\"ok\":false,\"error\":\"An export is already running\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = body.split("\n");
        Map<String, String> opts = parseQuery(lines[0]);
        List<Exporter.Segment> segments = new ArrayList<>();
        int w = 0, h = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t");
            if (parts.length < 3) continue;
            Path f = clipFile(parts[0]);
            if (f == null) continue;
            double start = Double.parseDouble(parts[1]), end = Double.parseDouble(parts[2]);
            if (end <= start) continue;
            segments.add(new Exporter.Segment(f, start, end));
            if (w == 0) {
                Cached c = probeCache.get(parts[0]);
                ClipInfo info = c != null ? c.info() : ClipInfo.probe(f);
                if (info != null) {
                    w = info.width();
                    h = info.height();
                }
            }
        }
        if (segments.isEmpty()) {
            sendJson(ex, "{\"ok\":false,\"error\":\"No clips selected\"}");
            return;
        }
        boolean useMusic = "1".equals(opts.get("useMusic")) && music != null;
        Path output = outDir.resolve("montage_" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now()) + ".mp4");
        Exporter.Settings settings = new Exporter.Settings(segments, w > 0 ? w : 1920, h > 0 ? h : 1080,
                useMusic ? music : null, num(opts.get("musicStart"), 0), num(opts.get("musicVolume"), 0.8),
                num(opts.get("gameVolume"), 0.5), output);

        state = "running";
        progress = 0;
        message = "Starting";
        lastOutput = null;
        Thread t = new Thread(() -> {
            try {
                Exporter.export(settings, (p, msg) -> {
                    progress = p;
                    message = msg;
                });
                lastOutput = output;
                message = "Saved " + output.getFileName();
                state = "done";
            } catch (Throwable e) {
                log("Export failed: " + e);
                e.printStackTrace();
                message = "Export failed: " + e.getMessage();
                state = "error";
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }
        }, "export");
        t.start();
        sendJson(ex, "{\"ok\":true}");
    }

    private static String status() {
        return "{\"state\":" + Json.str(state) + ",\"progress\":" + Json.num(progress) + ",\"message\":" + Json.str(message)
                + ",\"output\":" + Json.str(lastOutput == null ? null : lastOutput.toString()) + "}";
    }

    private static void reveal(HttpExchange ex) throws IOException {
        Path target = lastOutput != null ? lastOutput : outDir;
        if (Files.isRegularFile(target)) {
            new ProcessBuilder("explorer.exe", "/select,", target.toString()).start();
        } else {
            new ProcessBuilder("explorer.exe", target.toString()).start();
        }
        sendJson(ex, "{\"ok\":true}");
    }

    // ---------------------------------------------------------------- small helpers

    private static double num(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return parseQuery(q == null ? "" : q);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            if (body.length > 0) out.write(body);
        }
    }
}
