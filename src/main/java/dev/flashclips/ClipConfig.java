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

    /** How many seconds of history a clip contains (at least). */
    public int bufferSeconds = 30;
    /** How many seconds after a kill the kill clip is saved. */
    public int killClipDelaySeconds = 3;
    /**
     * Length of Flashback's internal replay chunks. A clip always starts at a chunk boundary,
     * so clips are between bufferSeconds and bufferSeconds + chunkSeconds long.
     * Smaller = tighter clips but Flashback takes a world snapshot more often.
     */
    public int chunkSeconds = 10;
    /** Automatically start a Flashback recording (the buffer) when you join a world/server. */
    public boolean autoStartBuffer = true;
    /** Delete chunks older than the buffer from the running buffer recording, so it never grows. */
    public boolean trimOldChunks = true;
    /** Throw the buffer recording away when you leave, instead of saving it as a normal Flashback replay. */
    public boolean discardBufferOnLeave = true;
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

    public int bufferTicks() {
        return Math.max(1, bufferSeconds) * 20;
    }

    public int chunkTicks() {
        return Math.max(1, chunkSeconds) * 20;
    }

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
