package dev.flashclips.mixin;

import com.moulberry.flashback.Flashback;
import dev.flashclips.ClipManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Flashback.class, remap = false)
public class FlashbackMixin {
    /**
     * Runs when Flashback finishes a recording (e.g. on disconnect). Saves any kill clips still
     * waiting on their 3 second delay, then throws the buffer recording away instead of saving it.
     */
    @Inject(method = "finishRecordingReplay()V", at = @At("HEAD"), cancellable = true)
    private static void flashclips$beforeFinish(CallbackInfo ci) {
        if (ClipManager.beforeFlashbackFinish()) {
            ci.cancel();
        }
    }
}
