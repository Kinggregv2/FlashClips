package dev.flashclips;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ImageFrame;
import com.moulberry.flashback.exporting.SaveableFramebuffer;
import com.moulberry.flashback.exporting.SaveableFramebufferQueue;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/**
 * Rolling screen recording for montage clips.
 *
 * Render thread: every frame (capped at screenFps) the world image is copied into a capture target, the GUI is
 * drawn into it again without the chat layer (see GuiRendererMixin), and the result is read back
 * asynchronously with Flashback's framebuffer download queue. Frames go to ScreenEncoder, which keeps the
 * last few seconds as compressed H.264 in memory.
 *
 * Main thread: a totem pop or kill schedules a save covering [event - before, event + after].
 * Events close together are merged into one video.
 */
public final class ScreenClips {
    // chat isolation state (render thread)
    public static int chatStratum = -1;
    public static boolean traversingChat = false;

    private static TextureTarget captureTarget;
    private static SaveableFramebufferQueue queue;
    private static int queueWidth, queueHeight;
    private static final ArrayDeque<Long> pendingTimes = new ArrayDeque<>();
    private static long nextFrameNanos;
    private static boolean disabledAfterError;

    private static Pending pending;

    private static final class Pending {
        long startMs;
        long endMs;
        final LinkedHashSet<String> labels = new LinkedHashSet<>();
        final LocalDateTime time = LocalDateTime.now();
    }

    private ScreenClips() {}

    public static void init() {
        // nothing to set up eagerly; the encoder starts with the first captured frame
    }

    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Whether frames should be captured right now. */
    public static boolean isRecording() {
        if (disabledAfterError || ScreenEncoder.failed || !FlashClips.CONFIG.screenBufferEnabled) return false;
        Minecraft mc = Minecraft.getInstance();
        return FlashClips.isInWorld() && mc.level != null && mc.player != null
                && !Flashback.isInReplay() && !Flashback.isExporting();
    }

    // ---------------------------------------------------------------- events (main thread)

    public static void onEvent(String label) {
        if (!isRecording()) return;
        ClipConfig cfg = FlashClips.CONFIG;
        long now = nowMs();
        long before = (long) (cfg.screenSecondsBefore * 1000);
        long after = (long) (cfg.screenSecondsAfter * 1000);
        long max = (long) (cfg.screenMaxClipSeconds * 1000);

        if (pending != null && now - before <= pending.endMs) {
            pending.endMs = Math.max(pending.endMs, Math.min(now + after, pending.startMs + max));
            pending.labels.add(label);
            return;
        }
        pending = new Pending();
        pending.startMs = now - before;
        pending.endMs = now + after;
        pending.labels.add(label);
    }

    public static void tick() {
        if (pending != null && nowMs() >= pending.endMs) {
            Pending p = pending;
            pending = null;
            ScreenEncoder.requestSave(p.startMs, p.endMs, String.join("+", p.labels), p.time);
        }
    }

    // ---------------------------------------------------------------- capture (render thread)

    /** Called before the GUI is drawn. Returns true if this frame is captured. */
    public static boolean beginFrame(RenderTarget main) {
        if (!isRecording() || main == null) {
            releaseIfIdle();
            return false;
        }
        long now = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(1, FlashClips.CONFIG.screenFps);
        if (now < nextFrameNanos) return false;
        nextFrameNanos = Math.max(nextFrameNanos + interval, now - interval);

        try {
            int w = main.width, h = main.height;
            if (captureTarget == null) {
                GpuFormat depth = main.getDepthTexture() != null ? main.getDepthTexture().getFormat() : null;
                captureTarget = new TextureTarget("flashclips_capture", w, h, main.getColorTexture().getFormat(), depth);
            } else if (captureTarget.width != w || captureTarget.height != h) {
                captureTarget.resize(w, h);
            }
            captureTarget.copyColorFrom(main);
            if (captureTarget.getDepthTexture() != null) {
                RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(captureTarget.getDepthTexture(), 1.0);
            }
            return true;
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Screen recorder failed to prepare a frame; disabling it", t);
            disabledAfterError = true;
            return false;
        }
    }

    public static RenderTarget captureTarget() {
        return captureTarget;
    }

    /** Called after the GUI is drawn: start reading the captured frame back, and hand finished frames to the encoder. */
    public static void endFrame() {
        try {
            int srcW = captureTarget.width, srcH = captureTarget.height;
            int outW, outH;
            int wantH = FlashClips.CONFIG.screenHeight;
            if (wantH > 0 && wantH < srcH) {
                outH = wantH;
                outW = (int) Math.round(srcW * (double) wantH / srcH);
            } else {
                outW = srcW;
                outH = srcH;
            }
            outW = Math.max(2, outW & ~1);
            outH = Math.max(2, outH & ~1);

            if (queue == null || queueWidth != outW || queueHeight != outH) {
                if (queue != null) queue.close();
                pendingTimes.clear();
                queue = new SaveableFramebufferQueue(outW, outH);
                queueWidth = outW;
                queueHeight = outH;
            }

            if (queue.pendingCount() < 4) {
                SaveableFramebuffer fb = queue.take();
                queue.startDownload(captureTarget, fb, true);
                pendingTimes.add(nowMs());
            }
            drain();
        } catch (Throwable t) {
            FlashClips.LOGGER.error("Screen recorder failed to capture a frame; disabling it", t);
            disabledAfterError = true;
        }
    }

    private static void drain() {
        ImageFrame frame;
        while ((frame = queue.finishDownload()) != null) {
            Long t = pendingTimes.poll();
            ScreenEncoder.submit(frame, t != null ? t : nowMs());
        }
    }

    private static void releaseIfIdle() {
        if (queue != null && !Minecraft.getInstance().isSameThread()) return;
        if (queue != null) {
            try {
                drain();
                if (queue.pendingCount() == 0) {
                    queue.close();
                    queue = null;
                    pendingTimes.clear();
                }
            } catch (Throwable t) {
                queue = null;
            }
        }
        if (queue == null && captureTarget != null) {
            captureTarget.destroyBuffers();
            captureTarget = null;
        }
    }
}
