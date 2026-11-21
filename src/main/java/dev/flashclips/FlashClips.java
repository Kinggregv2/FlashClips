package dev.flashclips;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class FlashClips implements ClientModInitializer {
    public static final String MOD_ID = "flashclips";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlashClips");

    public static ClipConfig CONFIG = new ClipConfig();
    public static KeyMapping clipKey;

    /** The Flashback recorder this mod started as its buffer (null if the current recording is the user's own). */
    public static volatile Recorder ownedRecorder;

    private static boolean inWorld = false;
    private static int startDelay = -1;
    private static int watchdog = 0;
    private static long clientTicks = 0;

    @Override
    public void onInitializeClient() {
        CONFIG = ClipConfig.load(FabricLoader.getInstance().getConfigDir().resolve("flashclips.json"));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keybinds"));
        clipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.flashclips.clip",
                InputConstants.Type.KEYBOARD, 296 /* F7 */, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            inWorld = true;
            startDelay = 40; // give Flashback (and its own auto-start) time to settle
            KillTracker.reset();
            ClipManager.reset();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            inWorld = false;
            startDelay = -1;
            ownedRecorder = null;
            KillTracker.reset();
            ClipManager.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(FlashClips::onEndTick);

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) KillTracker.onAttack(player, entity);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) KillTracker.onUseBlock(player, level, hit);
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) KillTracker.onGameMessage(message);
        });

        migrateOldClips();
        ScreenClips.init();
    }

    public static long ticks() {
        return clientTicks;
    }

    private static void onEndTick(Minecraft mc) {
        clientTicks++;
        if (mc.player == null || mc.level == null || !inWorld || Flashback.isInReplay()) {
            while (clipKey.consumeClick()) { /* swallow */ }
            return;
        }

        if (startDelay > 0 && --startDelay == 0) {
            ensureBufferRunning(mc, true);
        } else if (startDelay <= 0 && ++watchdog >= 200) {
            watchdog = 0;
            ensureBufferRunning(mc, false);
        }

        while (clipKey.consumeClick()) {
            ClipManager.requestManualClip();
        }

        KillTracker.tick(mc);
        ClipManager.tick();
        ScreenClips.tick();
    }

    public static boolean isInWorld() {
        return inWorld;
    }

    private static void ensureBufferRunning(Minecraft mc, boolean announce) {
        if (!CONFIG.autoStartBuffer || Flashback.RECORDER != null || Flashback.isExporting()) return;
        try {
            Flashback.startRecordingReplay();
        } catch (Throwable t) {
            LOGGER.error("Failed to start Flashback recording", t);
        }
        if (Flashback.RECORDER != null) {
            ownedRecorder = Flashback.RECORDER;
            if (announce) {
                message(Component.literal("Clip buffer running (" + CONFIG.bufferSeconds + "s). Press ")
                        .append(clipKey.getTranslatedKeyMessage())
                        .append(" to clip."), ChatFormatting.GRAY);
            }
        } else if (announce) {
            message(Component.literal("Flashback did not start recording, so clipping is unavailable."), ChatFormatting.RED);
        }
    }

    public static boolean ownsCurrentRecording() {
        Recorder r = Flashback.RECORDER;
        return r != null && r == ownedRecorder;
    }

    /** Inside Flashback's replay folder, so the clips show up (as folders) in Flashback's replay list. */
    public static Path clipsRoot() {
        return Flashback.getReplayFolder().resolve("clips");
    }

    /** Temporary files while a replay clip is assembled; kept out of the replay folder so it never shows in the list. */
    public static Path stagingRoot() {
        return Flashback.getDataDirectory().resolve("flashclips-staging");
    }

    /** Plain video clips for montages; deliberately outside Flashback's folders. */
    public static Path montageRoot() {
        String override = System.getProperty("flashclips.montageDir"); // used by the standalone encoder test
        if (override != null) return Path.of(override);
        return FabricLoader.getInstance().getGameDir().resolve("montage clips");
    }

    /** Older versions saved replay clips to flashback/clips; move them into the replay list once. */
    private static void migrateOldClips() {
        try {
            Path old = FabricLoader.getInstance().getGameDir().resolve("flashback").resolve("clips");
            Path now = clipsRoot();
            if (java.nio.file.Files.isDirectory(old) && !old.equals(now) && !java.nio.file.Files.exists(now)) {
                java.nio.file.Files.createDirectories(now.getParent());
                java.nio.file.Files.move(old, now);
                LOGGER.info("Moved old clips folder {} -> {}", old, now);
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't move old clips folder", e);
        }
    }

    public static void message(Component text, ChatFormatting colour) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[FlashClips] ").withStyle(ChatFormatting.GOLD)
                        .append(text.copy().withStyle(colour)));
            } else {
                LOGGER.info(text.getString());
            }
        });
    }
}
