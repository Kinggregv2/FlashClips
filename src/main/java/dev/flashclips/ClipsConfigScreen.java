package dev.flashclips;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen (Mod Menu → Clips → config). Built with Cloth Config; only loaded when Cloth Config is installed. */
final class ClipsConfigScreen {
    private ClipsConfigScreen() {}

    /** Sliders work in tenths of a second. */
    private static Component seconds(int tenths) {
        return Component.literal(String.format(java.util.Locale.ROOT, "%.1f s", tenths / 10.0));
    }

    static Screen create(Screen parent) {
        ClipConfig cfg = FlashClips.CONFIG;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Clips"));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("Recording"));

        general.addEntry(entries.startBooleanToggle(Component.literal("Recording buffer"), cfg.screenBufferEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Keep recording the screen in the background so pops, kills and the clip key can save a video."))
                .setSaveConsumer(v -> cfg.screenBufferEnabled = v)
                .build());

        general.addEntry(entries.startIntSlider(Component.literal("Before pop/kill"),
                        (int) Math.round(cfg.screenSecondsBefore * 10), 0, 100)
                .setDefaultValue(10)
                .setTextGetter(ClipsConfigScreen::seconds)
                .setTooltip(Component.literal("How much video before the pop or kill is kept."))
                .setSaveConsumer(v -> cfg.screenSecondsBefore = v / 10.0)
                .build());

        general.addEntry(entries.startIntSlider(Component.literal("After pop/kill"),
                        (int) Math.round(cfg.screenSecondsAfter * 10), 0, 100)
                .setDefaultValue(10)
                .setTextGetter(ClipsConfigScreen::seconds)
                .setTooltip(Component.literal("How long it keeps recording after the pop or kill before saving."))
                .setSaveConsumer(v -> cfg.screenSecondsAfter = v / 10.0)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Record game audio"), cfg.screenAudio)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.screenAudio = v)
                .build());

        builder.setSavingRunnable(() -> {
            cfg.screenMaxClipSeconds = Math.max(cfg.screenMaxClipSeconds, cfg.screenSecondsBefore + cfg.screenSecondsAfter);
            cfg.save();
            if (cfg.screenBufferEnabled && cfg.screenAudio) AudioCapture.start();
        });
        return builder.build();
    }
}
