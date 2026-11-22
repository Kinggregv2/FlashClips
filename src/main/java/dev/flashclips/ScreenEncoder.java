package dev.flashclips;

import com.moulberry.flashback.exporting.ImageFrame;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.swscale.SwsFilter;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Encodes captured frames to H.264 on a background thread (using the FFmpeg that ships inside Flashback)
 * and keeps the last few seconds of packets in memory. Saving just copies the right packets into an
 * mp4/mkv file, no re-encoding.
 */
public final class ScreenEncoder {
    public static volatile boolean failed = false;

    private record Item(ImageFrame frame, long ms) {}

    private record Packet(long pts, boolean key, byte[] data) {}

    private record SaveRequest(long startMs, long endMs, String label, LocalDateTime time) {}

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final ArrayBlockingQueue<Item> frames = new ArrayBlockingQueue<>(6);
    private static final ConcurrentLinkedQueue<SaveRequest> saveRequests = new ConcurrentLinkedQueue<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlashClips-VideoWriter");
        t.setDaemon(true);
        return t;
    });
    private static Thread thread;

    // ---- encoder-thread state ----
    private static AVCodecContext ctx;
    private static AVFrame yuv;
    private static AVPacket pkt;
    private static SwsContext sws;
    private static int encW, encH;
    private static long lastPts = Long.MIN_VALUE;
    private static final ArrayDeque<Packet> ring = new ArrayDeque<>();
    private static final List<SaveRequest> waiting = new ArrayList<>();

    private ScreenEncoder() {}

    public static void submit(ImageFrame frame, long ms) {
        if (failed) {
            frame.close();
            return;
        }
        ensureThread();
        if (!frames.offer(new Item(frame, ms))) {
            frame.close(); // encoder is behind; drop this frame
        }
    }

    public static void requestSave(long startMs, long endMs, String label, LocalDateTime time) {
        if (failed) return;
        ensureThread();
        saveRequests.add(new SaveRequest(startMs, endMs, label, time));
    }

    private static synchronized void ensureThread() {
        if (thread != null) return;
        thread = new Thread(ScreenEncoder::run, "FlashClips-ScreenEncoder");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        while (true) {
            Item item = null;
            try {
                item = frames.poll(100, TimeUnit.MILLISECONDS);
                if (item != null && !failed) encode(item);
                handleSaves();
            } catch (Throwable t) {
                FlashClips.LOGGER.error("Screen encoder error; montage clips disabled", t);
                failed = true;
            } finally {
                if (item != null) item.frame().close();
            }
            if (failed) {
                Item rest;
                while ((rest = frames.poll()) != null) rest.frame().close();
            }
        }
    }

    // ---------------------------------------------------------------- encoding

    private static void encode(Item item) {
        ImageFrame f = item.frame();
        int w = f.width, h = f.height;
        if (ctx == null || w != encW || h != encH) {
            closeEncoder();
            ring.clear();
            if (!openEncoder(w, h)) {
                failed = true;
                return;
            }
        }

        int srcFormat = f.ffmpegPixelFormat();
        sws = sws_getCachedContext(sws, w, h, srcFormat, w, h, ctx.pix_fmt(), SWS_BILINEAR,
                (SwsFilter) null, (SwsFilter) null, (DoublePointer) null);
        if (sws == null) throw new IllegalStateException("sws_getCachedContext failed");

        BytePointer src = new BytePointer() {
            {
                address = f.pixels;
                position = 0;
                limit = f.size;
                capacity = f.size;
            }
        };
        try (PointerPointer<BytePointer> srcData = new PointerPointer<>(4);
             IntPointer srcLines = new IntPointer(4)) {
            av_image_fill_arrays(srcData, srcLines, src, srcFormat, w, h, 1);
            av_frame_make_writable(yuv);
            sws_scale(sws, srcData, srcLines, 0, h, yuv.data(), yuv.linesize());
        }

        long pts = item.ms();
        if (pts <= lastPts) pts = lastPts + 1;
        lastPts = pts;
        yuv.pts(pts);

        if (avcodec_send_frame(ctx, yuv) < 0) return;
        while (avcodec_receive_packet(ctx, pkt) >= 0) {
            int size = pkt.size();
            byte[] data = new byte[size];
            pkt.data().capacity(size).get(data);
            ring.add(new Packet(pkt.pts(), (pkt.flags() & AV_PKT_FLAG_KEY) != 0, data));
            av_packet_unref(pkt);
        }

        ClipConfig cfg = FlashClips.CONFIG;
        long keepMs = (long) ((cfg.screenMaxClipSeconds + cfg.screenSecondsBefore + 3) * 1000);
        while (ring.size() > 1 && ring.peekLast().pts() - ring.peekFirst().pts() > keepMs) {
            ring.pollFirst();
        }
    }

    private static boolean openEncoder(int w, int h) {
        ClipConfig cfg = FlashClips.CONFIG;
        List<String> names = "auto".equalsIgnoreCase(cfg.screenEncoder)
                ? List.of("h264_nvenc", "h264_amf", "h264_qsv", "libx264", "h264_mf", "libopenh264", "mpeg4")
                : List.of(cfg.screenEncoder);
        int fps = Math.max(1, cfg.screenFps);
        String q = Integer.toString(cfg.screenQuality);
        long bitrate = Math.max(2_000_000L, (long) (w * (long) h * fps * 0.12));

        for (String name : names) {
            AVCodec codec = avcodec_find_encoder_by_name(name);
            if (codec == null) continue;
            AVCodecContext c = avcodec_alloc_context3(codec);
            if (c == null) continue;
            c.width(w);
            c.height(h);
            c.time_base(av_make_q(1, 1000));
            c.framerate(av_make_q(fps, 1));
            c.gop_size(Math.max(1, fps / 2));
            c.max_b_frames(0);
            c.pix_fmt(name.equals("h264_qsv") ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P);
            c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

            AVDictionary opts = new AVDictionary(null);
            switch (name) {
                case "h264_nvenc" -> {
                    av_dict_set(opts, "preset", "p4", 0);
                    av_dict_set(opts, "rc", "vbr", 0);
                    av_dict_set(opts, "cq", q, 0);
                    av_dict_set(opts, "zerolatency", "1", 0);
                    c.bit_rate(0);
                }
                case "h264_amf" -> {
                    av_dict_set(opts, "quality", "balanced", 0);
                    av_dict_set(opts, "rc", "cqp", 0);
                    av_dict_set(opts, "qp_i", q, 0);
                    av_dict_set(opts, "qp_p", q, 0);
                }
                case "h264_qsv" -> {
                    av_dict_set(opts, "preset", "faster", 0);
                    c.global_quality(cfg.screenQuality);
                }
                case "libx264" -> {
                    av_dict_set(opts, "preset", "veryfast", 0);
                    av_dict_set(opts, "tune", "zerolatency", 0);
                    av_dict_set(opts, "crf", q, 0);
                }
                default -> c.bit_rate(bitrate);
            }

            int r = avcodec_open2(c, codec, opts);
            av_dict_free(opts);
            if (r < 0) {
                FlashClips.LOGGER.info("Video encoder {} not usable here ({}), trying next", name, r);
                avcodec_free_context(c);
                continue;
            }

            ctx = c;
            encW = w;
            encH = h;
            yuv = av_frame_alloc();
            yuv.format(c.pix_fmt());
            yuv.width(w);
            yuv.height(h);
            av_frame_get_buffer(yuv, 32);
            pkt = av_packet_alloc();
            lastPts = Long.MIN_VALUE;
            FlashClips.LOGGER.info("Screen recorder using encoder {} at {}x{} {}fps", name, w, h, fps);
            return true;
        }
        FlashClips.LOGGER.error("No working H.264/MPEG-4 encoder found; montage clips disabled");
        FlashClips.message(net.minecraft.network.chat.Component.literal("Screen recording unavailable: no working video encoder."),
                net.minecraft.ChatFormatting.RED);
        return false;
    }

    private static void closeEncoder() {
        if (ctx != null) avcodec_free_context(ctx);
        if (yuv != null) av_frame_free(yuv);
        if (pkt != null) av_packet_free(pkt);
        if (sws != null) sws_freeContext(sws);
        ctx = null;
        yuv = null;
        pkt = null;
        sws = null;
    }

    // ---------------------------------------------------------------- saving

    private static void handleSaves() {
        SaveRequest r;
        while ((r = saveRequests.poll()) != null) waiting.add(r);
        if (waiting.isEmpty()) return;

        long now = ScreenClips.nowMs();
        Iterator<SaveRequest> it = waiting.iterator();
        while (it.hasNext()) {
            SaveRequest req = it.next();
            boolean haveEnd = !ring.isEmpty() && ring.peekLast().pts() >= req.endMs();
            if (!haveEnd && now - req.endMs() < 1500) continue; // wait for the last frames
            it.remove();
            if (ctx == null || ring.isEmpty()) continue;

            List<Packet> list = new ArrayList<>();
            Packet[] all = ring.toArray(new Packet[0]);
            int start = -1;
            for (int i = 0; i < all.length; i++) {
                if (all[i].key() && all[i].pts() <= req.startMs()) start = i;
            }
            if (start < 0) {
                for (int i = 0; i < all.length; i++) {
                    if (all[i].key()) {
                        start = i;
                        break;
                    }
                }
            }
            if (start < 0) continue;
            for (int i = start; i < all.length && all[i].pts() <= req.endMs(); i++) list.add(all[i]);
            if (list.isEmpty()) continue;

            AVCodecParameters par = avcodec_parameters_alloc();
            avcodec_parameters_from_context(par, ctx);
            WRITER.submit(() -> write(par, list, req));
        }
    }

    private static void write(AVCodecParameters par, List<Packet> packets, SaveRequest req) {
        AVFormatContext oc = new AVFormatContext(null);
        AVPacket p = null;
        boolean opened = false;
        Path out = null;
        try {
            Path dir = FlashClips.montageRoot();
            Files.createDirectories(dir);
            String ext = "mkv".equalsIgnoreCase(FlashClips.CONFIG.screenContainer) ? "mkv" : "mp4";
            String base = FILE_TIME.format(req.time()) + "_" + req.label();
            out = dir.resolve(base + "." + ext);
            for (int n = 2; Files.exists(out); n++) out = dir.resolve(base + "_" + n + "." + ext);
            String path = out.toAbsolutePath().toString();

            check(avformat_alloc_output_context2(oc, (AVOutputFormat) null, (String) null, path), "alloc output");
            AVStream st = avformat_new_stream(oc, null);
            if (st == null) throw new IllegalStateException("avformat_new_stream failed");
            check(avcodec_parameters_copy(st.codecpar(), par), "copy codec parameters");
            st.codecpar().codec_tag(0);
            AVRational ms = av_make_q(1, 1000);
            st.time_base(ms);

            // Optional AAC audio track from the game-audio capture
            boolean withAudio = FlashClips.CONFIG.screenAudio && AudioCapture.isRunning();
            AVCodecContext ac = null;
            AVStream ast = null;
            if (withAudio) {
                AVCodec aac = avcodec_find_encoder(AV_CODEC_ID_AAC);
                ac = aac == null ? null : avcodec_alloc_context3(aac);
                if (ac != null) {
                    ac.sample_fmt(AV_SAMPLE_FMT_FLTP);
                    ac.sample_rate(AudioCapture.RATE);
                    ac.bit_rate(192_000);
                    av_channel_layout_default(ac.ch_layout(), 2);
                    ac.time_base(av_make_q(1, AudioCapture.RATE));
                    if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) != 0) ac.flags(ac.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
                    if (avcodec_open2(ac, aac, (AVDictionary) null) >= 0) {
                        ast = avformat_new_stream(oc, null);
                        avcodec_parameters_from_context(ast.codecpar(), ac);
                        ast.time_base(av_make_q(1, AudioCapture.RATE));
                    } else {
                        avcodec_free_context(ac);
                        ac = null;
                    }
                }
                if (ac == null) FlashClips.LOGGER.warn("AAC encoder unavailable; saving clip without sound");
            }
            audioCtx = ac;

            AVIOContext pb = new AVIOContext(null);
            check(avio_open(pb, path, AVIO_FLAG_WRITE), "open file");
            oc.pb(pb);
            opened = true;
            check(avformat_write_header(oc, (AVDictionary) null), "write header");

            long first = packets.get(0).pts();
            p = av_packet_alloc();
            for (Packet pk : packets) {
                check(av_new_packet(p, pk.data().length), "alloc packet");
                p.data().capacity(pk.data().length).put(pk.data());
                long t = av_rescale_q(pk.pts() - first, ms, st.time_base());
                p.pts(t);
                p.dts(t);
                p.stream_index(st.index());
                if (pk.key()) p.flags(p.flags() | AV_PKT_FLAG_KEY);
                check(av_interleaved_write_frame(oc, p), "write packet");
                av_packet_unref(p);
            }

            if (ac != null) {
                long lastMs = packets.get(packets.size() - 1).pts();
                long frameMs = Math.max(1, 1000 / Math.max(1, FlashClips.CONFIG.screenFps));
                int totalFrames = (int) ((lastMs - first + frameMs) * AudioCapture.RATE / 1000);
                byte[] pcm = AudioCapture.extract(first * 1_000_000L, totalFrames);
                writeAudio(oc, ac, ast, p, pcm, totalFrames);
            }
            check(av_write_trailer(oc), "write trailer");
            FlashClips.LOGGER.info("Saved montage clip {}", out);
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Failed to write montage clip {}", out, t);
        } finally {
            if (audioCtx != null) avcodec_free_context(audioCtx);
            audioCtx = null;
            if (p != null) av_packet_free(p);
            if (opened) avio_closep(oc.pb());
            avformat_free_context(oc);
            avcodec_parameters_free(par);
        }
    }

    /** Writer-thread only: the audio encoder of the clip being written (freed in write's finally). */
    private static AVCodecContext audioCtx;

    /** Encodes 16-bit stereo PCM to AAC and writes it as the clip's audio stream, starting at time 0. */
    private static void writeAudio(AVFormatContext oc, AVCodecContext ac, AVStream ast, AVPacket p, byte[] pcm, int totalFrames) {
        int frameSize = ac.frame_size() > 0 ? ac.frame_size() : 1024;
        AVFrame af = av_frame_alloc();
        try {
            af.nb_samples(frameSize);
            af.format(AV_SAMPLE_FMT_FLTP);
            af.sample_rate(AudioCapture.RATE);
            av_channel_layout_copy(af.ch_layout(), ac.ch_layout());
            check(av_frame_get_buffer(af, 0), "alloc audio frame");

            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            float[] left = new float[frameSize];
            float[] right = new float[frameSize];
            for (int offset = 0; offset < totalFrames; offset += frameSize) {
                int n = Math.min(frameSize, totalFrames - offset);
                for (int i = 0; i < frameSize; i++) {
                    if (i < n) {
                        int idx = (offset + i) * AudioCapture.BYTES_PER_FRAME;
                        left[i] = bb.getShort(idx) / 32768f;
                        right[i] = bb.getShort(idx + 2) / 32768f;
                    } else {
                        left[i] = 0;
                        right[i] = 0;
                    }
                }
                check(av_frame_make_writable(af), "audio frame writable");
                new FloatPointer(af.data(0)).capacity(frameSize).put(left, 0, frameSize);
                new FloatPointer(af.data(1)).capacity(frameSize).put(right, 0, frameSize);
                af.pts(offset);
                check(avcodec_send_frame(ac, af), "encode audio");
                drainAudio(oc, ac, ast, p);
            }
            avcodec_send_frame(ac, null);
            drainAudio(oc, ac, ast, p);
        } finally {
            av_frame_free(af);
        }
    }

    private static void drainAudio(AVFormatContext oc, AVCodecContext ac, AVStream ast, AVPacket p) {
        while (avcodec_receive_packet(ac, p) >= 0) {
            av_packet_rescale_ts(p, ac.time_base(), ast.time_base());
            p.stream_index(ast.index());
            check(av_interleaved_write_frame(oc, p), "write audio packet");
            av_packet_unref(p);
        }
    }

    private static void check(int r, String what) {
        if (r < 0) throw new IllegalStateException(what + " failed (" + r + ")");
    }
}
