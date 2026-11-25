package dev.flashclips.editor;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** What the editor knows about one clip: length, size and where the pops/kills happen. */
public record ClipInfo(String name, double duration, int width, int height, boolean hasAudio,
                       List<Event> events, boolean estimated) {

    public record Event(String type, double t) {}

    private static final Pattern EVENT = Pattern.compile("\"type\":\"([^\"]+)\",\"t\":([0-9.]+)");

    /** "After" setting from config/clips.json; used to guess the event time in clips saved before times were stored. */
    static volatile double defaultAfter = 1.0;

    public static ClipInfo probe(Path file) {
        AVFormatContext fc = new AVFormatContext(null);
        if (avformat_open_input(fc, file.toAbsolutePath().toString(), null, (AVDictionary) null) < 0) return null;
        try {
            avformat_find_stream_info(fc, (AVDictionary) null);
            double duration = Math.max(0, fc.duration() / 1e6);
            int w = 0, h = 0;
            boolean audio = false;
            for (int i = 0; i < fc.nb_streams(); i++) {
                var par = fc.streams(i).codecpar();
                if (par.codec_type() == AVMEDIA_TYPE_VIDEO && w == 0) {
                    w = par.width();
                    h = par.height();
                } else if (par.codec_type() == AVMEDIA_TYPE_AUDIO) {
                    audio = true;
                }
            }

            List<Event> events = new ArrayList<>();
            AVDictionaryEntry comment = av_dict_get(fc.metadata(), "comment", null, 0);
            if (comment != null && comment.value() != null) {
                Matcher m = EVENT.matcher(comment.value().getString());
                while (m.find()) events.add(new Event(m.group(1), Double.parseDouble(m.group(2))));
            }

            boolean estimated = events.isEmpty();
            if (estimated) {
                String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                String type = n.contains("kill") ? "kill" : n.contains("pop") ? "pop" : "manual";
                double t = type.equals("manual") ? duration : Math.max(0, duration - defaultAfter);
                events.add(new Event(type, t));
            }
            return new ClipInfo(file.getFileName().toString(), duration, w, h, audio, events, estimated);
        } finally {
            avformat_close_input(fc);
        }
    }

    public double firstEvent() {
        return events.get(0).t();
    }

    public double lastEvent() {
        return events.get(events.size() - 1).t();
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(Json.str(name))
                .append(",\"duration\":").append(Json.num(duration))
                .append(",\"width\":").append(width)
                .append(",\"height\":").append(height)
                .append(",\"hasAudio\":").append(hasAudio)
                .append(",\"estimated\":").append(estimated)
                .append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":").append(Json.str(events.get(i).type()))
                    .append(",\"t\":").append(Json.num(events.get(i).t())).append('}');
        }
        return sb.append("]}").toString();
    }
}
