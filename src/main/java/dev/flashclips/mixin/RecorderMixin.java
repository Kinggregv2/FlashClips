package dev.flashclips.mixin;

import com.moulberry.flashback.io.AsyncReplaySaver;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import dev.flashclips.ClipManager;
import dev.flashclips.FlashClips;
import dev.flashclips.RecorderExt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(value = Recorder.class, remap = false)
public abstract class RecorderMixin implements RecorderExt {
    @Shadow private int writtenTicks;
    @Shadow @Final private FlashbackMeta metadata;
    @Shadow @Final private AsyncReplaySaver asyncReplaySaver;

    @Unique private int flashclips$chunksBefore;

    /**
     * Flashback starts a new replay chunk (each beginning with a full snapshot) every 6000 ticks.
     * Shorter chunks let us cut the newest ~30 seconds out as a standalone replay.
     * Returning 0 forces a chunk boundary right now (used when a clip is requested).
     */
    @ModifyConstant(method = "endTick", constant = @Constant(intValue = 6000))
    private int flashclips$chunkLength(int original) {
        if (ClipManager.forceSplit) return 0;
        return FlashClips.CONFIG.chunkTicks();
    }

    @Inject(method = "endTick", at = @At("HEAD"))
    private void flashclips$beforeEndTick(boolean close, CallbackInfo ci) {
        this.flashclips$chunksBefore = this.metadata.chunks.size();
    }

    @Inject(method = "endTick", at = @At("RETURN"))
    private void flashclips$afterEndTick(boolean close, CallbackInfo ci) {
        if (this.metadata.chunks.size() > this.flashclips$chunksBefore) {
            try {
                ClipManager.onChunkCompleted((Recorder) (Object) this);
            } catch (Throwable t) {
                FlashClips.LOGGER.error("FlashClips failed while handling a finished replay chunk", t);
            }
        }
    }

    @Override
    public int flashclips$getWrittenTicks() {
        return this.writtenTicks;
    }

    @Override
    public FlashbackMeta flashclips$getMetadata() {
        return this.metadata;
    }

    @Override
    public Path flashclips$getRecordFolder() {
        return ((AsyncReplaySaverAccessor) this.asyncReplaySaver).flashclips$getRecordFolder();
    }
}
