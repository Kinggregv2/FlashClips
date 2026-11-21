package dev.flashclips.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.flashclips.ScreenClips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

/**
 * Draws the GUI a second time into the screen recorder's capture target, skipping the draw
 * calls that belong to the chat layer. What you see on screen is unchanged.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private List<?> draws;
    @Shadow private RenderPipeline previousPipeline;

    @Unique private boolean flashclips$lastInChat;
    @Unique private int flashclips$chatStart = -1;
    @Unique private int flashclips$chatEnd = -1;
    @Unique private boolean flashclips$capturing;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void flashclips$prepareHead(CallbackInfo ci) {
        this.flashclips$lastInChat = false;
        this.flashclips$chatStart = -1;
        this.flashclips$chatEnd = -1;
    }

    @Inject(method = "addElementToMesh", at = @At("HEAD"))
    private void flashclips$trackChat(GuiElementRenderState element, CallbackInfo ci) {
        boolean inChat = ScreenClips.traversingChat;
        if (inChat != this.flashclips$lastInChat) {
            // Force a fresh draw call so chat never shares a draw with other GUI elements.
            this.previousPipeline = null;
            if (inChat) {
                this.flashclips$chatStart = this.draws.size();
            } else {
                this.flashclips$chatEnd = this.draws.size();
            }
            this.flashclips$lastInChat = inChat;
        }
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void flashclips$prepareReturn(CallbackInfo ci) {
        if (this.flashclips$chatStart >= 0 && this.flashclips$chatEnd < this.flashclips$chatStart) {
            this.flashclips$chatEnd = this.draws.size();
        }
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void flashclips$drawHead(CallbackInfo ci) {
        this.flashclips$capturing = ScreenClips.beginFrame(Minecraft.getInstance().gameRenderer.mainRenderTarget());
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V"))
    private void flashclips$alsoDrawToCapture(GuiRenderer self, Supplier<String> label, RenderTarget target,
                                              GpuBufferSlice transform, int from, int to, Operation<Void> original) {
        original.call(self, label, target, transform, from, to);
        if (!this.flashclips$capturing) return;
        RenderTarget capture = ScreenClips.captureTarget();
        if (capture == null) return;

        int chatStart = this.flashclips$chatStart;
        int chatEnd = this.flashclips$chatEnd;
        if (chatStart < 0) {
            if (to > from) original.call(self, label, capture, transform, from, to);
            return;
        }
        int aEnd = Math.min(to, chatStart);
        if (aEnd > from) original.call(self, label, capture, transform, from, aEnd);
        int bStart = Math.max(from, chatEnd);
        if (to > bStart) original.call(self, label, capture, transform, bStart, to);
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void flashclips$drawReturn(CallbackInfo ci) {
        if (this.flashclips$capturing) {
            this.flashclips$capturing = false;
            ScreenClips.endFrame();
        }
    }
}
