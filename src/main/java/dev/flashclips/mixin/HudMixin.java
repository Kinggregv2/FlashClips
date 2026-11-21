package dev.flashclips.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flashclips.ScreenClips;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public abstract class HudMixin {
    /**
     * Puts the chat into its own GUI layer (stratum), so the screen recorder can draw
     * a copy of the frame with every layer except chat.
     */
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Hud;extractChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void flashclips$isolateChat(Hud hud, GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
        if (!ScreenClips.isRecording()) {
            original.call(hud, graphics, delta);
            return;
        }
        graphics.nextStratum();
        ScreenClips.chatStratum = graphics.guiRenderState.strata.size() - 1;
        try {
            original.call(hud, graphics, delta);
        } finally {
            graphics.nextStratum();
        }
    }
}
