package dev.flashclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings, stored in config/flashclips.json. */
public class ClipConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A hit on a player only counts toward a kill if they die within this many seconds. */
    public int killCreditSeconds = 5;
    /** Players within this many blocks of a crystal / anchor you popped count as hit by you. */
    public double explosionCreditRadius = 12.0;
    /** Detect kills from death messages in chat. */
    public boolean detectKillsFromChat = true;
    /** Detect kills when a player you recently hit dies. */
    public boolean detectKillsFromHits = true;
    /**
     * Extra regexes for servers that use custom (non-vanilla) kill messages.
     * Each needs the named groups "victim" and "killer".
     */
    public List<String> customKillMessagePatterns = new ArrayList<>(List.of(
            "(?<victim>[A-Za-z0-9_]{1,16}) (?:was|has been|got) (?:slain|killed|shot|blown up|eliminated|murdered|destroyed|finished|obliterated|exploded)\\b.*?\\bby (?<killer>[A-Za-z0-9_]{1,16})",
            "(?<killer>[A-Za-z0-9_]{1,16}) (?:killed|slew|eliminated|murdered|destroyed|finished|obliterated) (?<victim>[A-Za-z0-9_]{1,16})"
    ));

    // ---- screen recording (montage clips) ----

    /** Keep a rolling screen recording and save a video when you pop a totem or get a kill. */
    public boolean screenBufferEnabled = true;
    /** Seconds of video kept from before the pop/kill. */
    public double screenSecondsBefore = 1.0;
    /** Seconds of video recorded after the pop/kill before the file is saved. */
    public double screenSecondsAfter = 1.0;
    /** A pop and a kill close together are merged into one video, up to this length. */
    public double screenMaxClipSeconds = 8.0;
    /** Recording frame rate. */
    public int screenFps = 60;
    /** Output height in pixels (width follows the window's aspect ratio). 0 = window resolution. */
    public int screenHeight = 0;
    /** "mp4" or "mkv". */
    public String screenContainer = "mp4";
    /** Encoder: "auto" tries NVIDIA, AMD, Intel, then CPU (libx264 / Media Foundation). Or name one, e.g. "h264_nvenc". */
    public String screenEncoder = "auto";
    /** Record the game's sound into clips (Windows: captures only Minecraft's own audio). */
    public boolean screenAudio = true;
    /** Constant-quality level (lower = better quality, bigger files). ~18-23 is sensible. */
    public int screenQuality = 20;

    public static ClipConfig load(Path file) {
        ClipConfig config = null;
        try {
            if (Files.exists(file)) {
                config = GSON.fromJson(Files.readString(file), ClipConfig.class);
            }
        } catch (Exception e) {
            FlashClips.LOGGER.error("Failed to read {}, using defaults", file, e);
        }
        if (config == null) config = new ClipConfig();
        if (config.customKillMessagePatterns == null) config.customKillMessagePatterns = new ArrayList<>();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config));
        } catch (Exception e) {
            FlashClips.LOGGER.error("Failed to write {}", file, e);
        }
        return config;
    }
}
