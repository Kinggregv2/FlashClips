package dev.flashclips.editor;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.swscale.SwsFilter;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Cuts each clip to [start, end), joins them, puts the music under it and writes one mp4.
 * Video is re-encoded (H.264, GPU encoder when available); audio is mixed and encoded to AAC.
 */
public final class Exporter {
    public static final int RATE = 48000;

    public record Segment(Path file, double start, double end) {}

    public record Settings(List<Segment> segments, int width, int height, Path music, double musicStart,
                           double musicVolume, double gameVolume, Path output) {}

    public interface Progress {
        void update(double fraction, String message);
    }

    // per-export state
    private final Settings s;
    private final Progress progress;
    private AVFormatContext oc;
    private AVCodecContext venc, aenc;
    private AVStream vst, ast;
    private AVFrame yuv;
    private AVPacket outPkt;
    private SwsContext sws;
    private long lastVideoPts = -1;
    private float[] game = new float[0]; // interleaved stereo game audio for the whole timeline

    private Exporter(Settings s, Progress progress) {
        this.s = s;
        this.progress = progress;
    }

    public static void export(Settings s, Progress progress) throws Exception {
        new Exporter(s, progress).run();
    }

    private void run() throws Exception {
        int w = Math.max(2, s.width() & ~1), h = Math.max(2, s.height() & ~1);
        double total = 0;
        for (Segment seg : s.segments()) total += Math.max(0, seg.end() - seg.start());
        if (total <= 0) throw new IllegalArgumentException("Nothing to export");
        int totalFrames = (int) Math.round(total * RATE);
        game = new float[totalFrames * 2];

        boolean opened = false;
        oc = new AVFormatContext(null);
        try {
            String out = s.output().toAbsolutePath().toString();
            check(avformat_alloc_output_context2(oc, (AVOutputFormat) null, (String) null, out), "create output");
            openVideoEncoder(w, h);
            openAudioEncoder();

            AVIOContext pb = new AVIOContext(null);
            check(avio_open(pb, out, AVIO_FLAG_WRITE), "open output file");
            oc.pb(pb);
            opened = true;
            check(avformat_write_header(oc, (AVDictionary) null), "write header");

            outPkt = av_packet_alloc();
            yuv = av_frame_alloc();
            yuv.format(venc.pix_fmt());
            yuv.width(w);
            yuv.height(h);
            check(av_frame_get_buffer(yuv, 32), "alloc frame");

            double offset = 0;
            for (int i = 0; i < s.segments().size(); i++) {
                Segment seg = s.segments().get(i);
                progress.update(0.9 * i / s.segments().size(), "Cutting " + seg.file().getFileName());
                cutSegment(seg, offset);
                offset += Math.max(0, seg.end() - seg.start());
            }
            check(avcodec_send_frame(venc, null), "flush video");
            drain(venc, vst);

            progress.update(0.92, "Mixing audio");
            float[] mix = mixAudio(totalFrames);
            encodeAudio(mix, totalFrames);

            check(av_write_trailer(oc), "finish file");
            progress.update(1.0, "Done");
        } finally {
            if (sws != null) sws_freeContext(sws);
            if (yuv != null) av_frame_free(yuv);
            if (outPkt != null) av_packet_free(outPkt);
            if (venc != null) avcodec_free_context(venc);
            if (aenc != null) avcodec_free_context(aenc);
            if (opened) avio_closep(oc.pb());
            avformat_free_context(oc);
        }
    }

    // ---------------------------------------------------------------- encoders

    private void openVideoEncoder(int w, int h) {
        for (String name : List.of("h264_nvenc", "h264_amf", "h264_qsv", "libx264", "h264_mf", "libopenh264", "mpeg4")) {
            AVCodec codec = avcodec_find_encoder_by_name(name);
            if (codec == null) continue;
            AVCodecContext c = avcodec_alloc_context3(codec);
            c.width(w);
            c.height(h);
            c.time_base(av_make_q(1, 1000));
            c.framerate(av_make_q(60, 1));
            c.gop_size(60);
            c.max_b_frames(0);
            c.pix_fmt(name.equals("h264_qsv") ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUV420P);
            if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) != 0) c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
            AVDictionary o = new AVDictionary(null);
            switch (name) {
                case "h264_nvenc" -> {
                    av_dict_set(o, "preset", "p6", 0);
                    av_dict_set(o, "rc", "vbr", 0);
                    av_dict_set(o, "cq", "18", 0);
                    c.bit_rate(0);
                }
                case "h264_amf" -> {
                    av_dict_set(o, "quality", "quality", 0);
                    av_dict_set(o, "rc", "cqp", 0);
                    av_dict_set(o, "qp_i", "18", 0);
                    av_dict_set(o, "qp_p", "18", 0);
                }
                case "h264_qsv" -> c.global_quality(18);
                case "libx264" -> {
                    av_dict_set(o, "preset", "medium", 0);
                    av_dict_set(o, "crf", "18", 0);
                }
                default -> c.bit_rate((long) w * h * 60 / 5);
            }
            int r = avcodec_open2(c, codec, o);
            av_dict_free(o);
            if (r < 0) {
                avcodec_free_context(c);
                continue;
            }
            venc = c;
            vst = avformat_new_stream(oc, null);
            check(avcodec_parameters_from_context(vst.codecpar(), c), "video stream");
            vst.time_base(av_make_q(1, 1000));
            Main.log("Video encoder: " + name + " " + w + "x" + h);
            return;
        }
        throw new IllegalStateException("No working video encoder found");
    }

    private void openAudioEncoder() {
        AVCodec aac = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (aac == null) throw new IllegalStateException("No AAC encoder");
        aenc = avcodec_alloc_context3(aac);
        aenc.sample_fmt(AV_SAMPLE_FMT_FLTP);
        aenc.sample_rate(RATE);
        aenc.bit_rate(256_000);
        av_channel_layout_default(aenc.ch_layout(), 2);
        aenc.time_base(av_make_q(1, RATE));
        if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) != 0) aenc.flags(aenc.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        check(avcodec_open2(aenc, aac, (AVDictionary) null), "open AAC encoder");
        ast = avformat_new_stream(oc, null);
        check(avcodec_parameters_from_context(ast.codecpar(), aenc), "audio stream");
        ast.time_base(av_make_q(1, RATE));
    }

    private void drain(AVCodecContext enc, AVStream st) {
        while (avcodec_receive_packet(enc, outPkt) >= 0) {
            av_packet_rescale_ts(outPkt, enc.time_base(), st.time_base());
            outPkt.stream_index(st.index());
            check(av_write_frame(oc, outPkt), "write packet");
            av_packet_unref(outPkt);
        }
    }

    // ---------------------------------------------------------------- one clip

    private void cutSegment(Segment seg, double offsetSec) {
        AVFormatContext in = new AVFormatContext(null);
        check(avformat_open_input(in, seg.file().toAbsolutePath().toString(), null, (AVDictionary) null), "open " + seg.file().getFileName());
        AVCodecContext vdec = null, adec = null;
        SwrContext swr = null;
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        try {
            avformat_find_stream_info(in, (AVDictionary) null);
            int vi = -1, ai = -1;
            for (int i = 0; i < in.nb_streams(); i++) {
                int type = in.streams(i).codecpar().codec_type();
                if (type == AVMEDIA_TYPE_VIDEO && vi < 0) vi = i;
                if (type == AVMEDIA_TYPE_AUDIO && ai < 0) ai = i;
            }
            if (vi < 0) throw new IllegalStateException(seg.file().getFileName() + " has no video");
            vdec = openDecoder(in.streams(vi).codecpar());
            double vtb = av_q2d(in.streams(vi).time_base());
            double atb = 0;
            AudioBuffer clipAudio = null;
            if (ai >= 0 && s.gameVolume() > 0) {
                adec = openDecoder(in.streams(ai).codecpar());
                atb = av_q2d(in.streams(ai).time_base());
                swr = resampler(adec);
                clipAudio = new AudioBuffer();
            }

            while (av_read_frame(in, pkt) >= 0) {
                if (pkt.stream_index() == vi) {
                    if (avcodec_send_packet(vdec, pkt) >= 0) receiveVideo(vdec, frame, vtb, seg, offsetSec);
                } else if (pkt.stream_index() == ai && adec != null) {
                    if (avcodec_send_packet(adec, pkt) >= 0) receiveAudio(adec, frame, swr, atb, clipAudio);
                }
                av_packet_unref(pkt);
            }
            avcodec_send_packet(vdec, null);
            receiveVideo(vdec, frame, vtb, seg, offsetSec);
            if (adec != null) {
                avcodec_send_packet(adec, null);
                receiveAudio(adec, frame, swr, atb, clipAudio);
                clipAudio.flush(swr);
                placeGameAudio(clipAudio, seg, offsetSec);
            }
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);
            if (swr != null) swr_free(swr);
            if (vdec != null) avcodec_free_context(vdec);
            if (adec != null) avcodec_free_context(adec);
            avformat_close_input(in);
        }
    }

    private void receiveVideo(AVCodecContext dec, AVFrame f, double tb, Segment seg, double offsetSec) {
        while (avcodec_receive_frame(dec, f) >= 0) {
            long ts = f.best_effort_timestamp();
            if (ts == AV_NOPTS_VALUE) ts = f.pts();
            double t = ts * tb;
            if (t >= seg.start() - 1e-6 && t < seg.end()) {
                long pts = Math.round((offsetSec + (t - seg.start())) * 1000);
                if (pts <= lastVideoPts) pts = lastVideoPts + 1;
                lastVideoPts = pts;
                sws = sws_getCachedContext(sws, f.width(), f.height(), f.format(), yuv.width(), yuv.height(),
                        venc.pix_fmt(), SWS_BICUBIC, (SwsFilter) null, (SwsFilter) null, (DoublePointer) null);
                check(av_frame_make_writable(yuv), "frame writable");
                sws_scale(sws, f.data(), f.linesize(), 0, f.height(), yuv.data(), yuv.linesize());
                yuv.pts(pts);
                check(avcodec_send_frame(venc, yuv), "encode video");
                drain(venc, vst);
            }
            av_frame_unref(f);
        }
    }

    // ---------------------------------------------------------------- audio

    /** Growable interleaved stereo float buffer, with the time of its first sample. */
    private static final class AudioBuffer {
        double t0 = Double.NaN;
        float[] data = new float[RATE * 2 * 4];
        int frames;

        void append(FloatPointer p, int n) {
            if ((frames + n) * 2 > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, (frames + n) * 2));
            p.get(data, frames * 2, n * 2);
            frames += n;
        }

        void flush(SwrContext swr) {
            int max = 4096;
            try (FloatPointer out = new FloatPointer(max * 2L); PointerPointer<FloatPointer> pp = new PointerPointer<>(1)) {
                pp.put(0, out);
                int got;
                while ((got = swr_convert(swr, pp, max, (PointerPointer<?>) null, 0)) > 0) append(out, got);
            }
        }
    }

    private static SwrContext resampler(AVCodecContext dec) {
        AVChannelLayout stereo = new AVChannelLayout();
        av_channel_layout_default(stereo, 2);
        SwrContext swr = new SwrContext(null);
        check(swr_alloc_set_opts2(swr, stereo, AV_SAMPLE_FMT_FLT, RATE, dec.ch_layout(), dec.sample_fmt(),
                dec.sample_rate(), 0, null), "set up resampler");
        check(swr_init(swr), "init resampler");
        return swr;
    }

    private static void receiveAudio(AVCodecContext dec, AVFrame f, SwrContext swr, double tb, AudioBuffer buf) {
        while (avcodec_receive_frame(dec, f) >= 0) {
            if (Double.isNaN(buf.t0)) {
                long ts = f.best_effort_timestamp();
                buf.t0 = ts == AV_NOPTS_VALUE ? 0 : ts * tb;
            }
            int max = swr_get_out_samples(swr, f.nb_samples());
            if (max <= 0) max = f.nb_samples() * 2 + 256;
            try (FloatPointer out = new FloatPointer(max * 2L); PointerPointer<FloatPointer> pp = new PointerPointer<>(1)) {
                pp.put(0, out);
                int got = swr_convert(swr, pp, max, f.extended_data(), f.nb_samples());
                if (got > 0) buf.append(out, got);
            }
            av_frame_unref(f);
        }
    }

    private void placeGameAudio(AudioBuffer a, Segment seg, double offsetSec) {
        if (a.frames == 0 || Double.isNaN(a.t0)) return;
        int src = (int) Math.round((seg.start() - a.t0) * RATE);
        int dst = (int) Math.round(offsetSec * RATE);
        int count = (int) Math.round((seg.end() - seg.start()) * RATE);
        int totalFrames = game.length / 2;
        for (int i = 0; i < count; i++) {
            int si = src + i, di = dst + i;
            if (si < 0 || si >= a.frames || di < 0 || di >= totalFrames) continue;
            game[di * 2] = a.data[si * 2];
            game[di * 2 + 1] = a.data[si * 2 + 1];
        }
    }

    private float[] decodeMusic() {
        AVFormatContext in = new AVFormatContext(null);
        check(avformat_open_input(in, s.music().toAbsolutePath().toString(), null, (AVDictionary) null), "open music");
        AVCodecContext dec = null;
        SwrContext swr = null;
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        AudioBuffer buf = new AudioBuffer();
        try {
            avformat_find_stream_info(in, (AVDictionary) null);
            int ai = -1;
            for (int i = 0; i < in.nb_streams(); i++) {
                if (in.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    ai = i;
                    break;
                }
            }
            if (ai < 0) throw new IllegalStateException("The music file has no audio");
            dec = openDecoder(in.streams(ai).codecpar());
            swr = resampler(dec);
            double tb = av_q2d(in.streams(ai).time_base());
            while (av_read_frame(in, pkt) >= 0) {
                if (pkt.stream_index() == ai && avcodec_send_packet(dec, pkt) >= 0) receiveAudio(dec, frame, swr, tb, buf);
                av_packet_unref(pkt);
            }
            avcodec_send_packet(dec, null);
            receiveAudio(dec, frame, swr, tb, buf);
            buf.flush(swr);
            return Arrays.copyOf(buf.data, buf.frames * 2);
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);
            if (swr != null) swr_free(swr);
            if (dec != null) avcodec_free_context(dec);
            avformat_close_input(in);
        }
    }

    private float[] mixAudio(int totalFrames) {
        float[] mix = new float[totalFrames * 2];
        float gv = (float) s.gameVolume();
        for (int i = 0; i < mix.length; i++) mix[i] = game[i] * gv;

        if (s.music() != null && s.musicVolume() > 0) {
            float[] music = decodeMusic();
            int musicFrames = music.length / 2;
            int start = (int) Math.round(Math.max(0, s.musicStart()) * RATE);
            float mv = (float) s.musicVolume();
            int fade = RATE; // fade the music out over the last second
            for (int i = 0; i < totalFrames; i++) {
                int m = start + i;
                if (m >= musicFrames) break;
                float f = mv * Math.min(1f, (totalFrames - i) / (float) fade);
                mix[i * 2] += music[m * 2] * f;
                mix[i * 2 + 1] += music[m * 2 + 1] * f;
            }
        }
        for (int i = 0; i < mix.length; i++) mix[i] = Math.max(-1f, Math.min(1f, mix[i]));
        return mix;
    }

    private void encodeAudio(float[] mix, int totalFrames) {
        int frameSize = aenc.frame_size() > 0 ? aenc.frame_size() : 1024;
        AVFrame af = av_frame_alloc();
        try {
            af.nb_samples(frameSize);
            af.format(AV_SAMPLE_FMT_FLTP);
            af.sample_rate(RATE);
            av_channel_layout_copy(af.ch_layout(), aenc.ch_layout());
            check(av_frame_get_buffer(af, 0), "alloc audio frame");
            float[] l = new float[frameSize], r = new float[frameSize];
            for (int off = 0; off < totalFrames; off += frameSize) {
                int n = Math.min(frameSize, totalFrames - off);
                Arrays.fill(l, 0);
                Arrays.fill(r, 0);
                for (int i = 0; i < n; i++) {
                    l[i] = mix[(off + i) * 2];
                    r[i] = mix[(off + i) * 2 + 1];
                }
                check(av_frame_make_writable(af), "audio frame writable");
                new FloatPointer(af.data(0)).capacity(frameSize).put(l, 0, frameSize);
                new FloatPointer(af.data(1)).capacity(frameSize).put(r, 0, frameSize);
                af.pts(off);
                check(avcodec_send_frame(aenc, af), "encode audio");
                drain(aenc, ast);
            }
            avcodec_send_frame(aenc, null);
            drain(aenc, ast);
        } finally {
            av_frame_free(af);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AVCodecContext openDecoder(AVCodecParameters par) {
        AVCodec codec = avcodec_find_decoder(par.codec_id());
        if (codec == null) throw new IllegalStateException("No decoder for " + avcodec_get_name(par.codec_id()).getString());
        AVCodecContext c = avcodec_alloc_context3(codec);
        check(avcodec_parameters_to_context(c, par), "decoder parameters");
        check(avcodec_open2(c, codec, (AVDictionary) null), "open decoder");
        return c;
    }

    private static void check(int r, String what) {
        if (r < 0) {
            byte[] buf = new byte[256];
            av_strerror(r, buf, buf.length);
            String msg = new String(buf).trim().replace("\0", "");
            throw new IllegalStateException(what + " failed: " + msg);
        }
    }
}
