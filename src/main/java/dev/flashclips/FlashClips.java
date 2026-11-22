package dev.flashclips;

import com.moulberry.flashback.Flashback;
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

/**
 * Chat-free rolling screen recording. Saves a short video when you pop someone's totem, get a kill,
 * or press the clip key. Uses the FFmpeg that ships inside Flashback for encoding.
 */
public class FlashClips implements ClientModInitializer {
    public static final String MOD_ID = "flashclips";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlashClips");

    public static ClipConfig CONFIG = new ClipConfig();
    public static KeyMapping clipKey;

    private static boolean inWorld = false;
    private static long clientTicks = 0;

    @Override
    public void onInitializeClient() {
        CONFIG = ClipConfig.load(FabricLoader.getInstance().getConfigDir().resolve("flashclips.json"));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "keybinds"));
        clipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.flashclips.clip",
                InputConstants.Type.KEYBOARD, 296 /* F7 */, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            inWorld = true;
            KillTracker.reset();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            inWorld = false;
            KillTracker.reset();
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
        while (clipKey.consumeClick()) {
            ScreenClips.saveNow("manual");
        }
        KillTracker.tick(mc);
        ScreenClips.tick();
    }

    public static boolean isInWorld() {
        return inWorld;
    }

    /** Where the videos go. */
    public static Path montageRoot() {
        String override = System.getProperty("flashclips.montageDir"); // used by the standalone encoder test
        if (override != null) return Path.of(override);
        return FabricLoader.getInstance().getGameDir().resolve("montage clips");
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
