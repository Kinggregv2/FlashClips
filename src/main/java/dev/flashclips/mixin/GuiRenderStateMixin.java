package dev.flashclips.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flashclips.ScreenClips;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    /** Flags while the chat layer is being walked, so GuiRendererMixin knows which draw calls are chat. */
    @WrapOperation(method = "traverse(Ljava/util/function/Consumer;Lnet/minecraft/client/renderer/state/gui/GuiRenderState$TraverseRange;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;traverse(Lnet/minecraft/client/renderer/state/gui/GuiRenderState$Node;Ljava/util/function/Consumer;)V"))
    private void flashclips$markChat(GuiRenderState self, GuiRenderState.Node node,
                                     Consumer<GuiRenderState.Node> consumer, Operation<Void> original) {
        int chat = ScreenClips.chatStratum;
        List<GuiRenderState.Node> strata = self.strata;
        if (chat < 0 || chat >= strata.size() || strata.get(chat) != node) {
            original.call(self, node, consumer);
            return;
        }
        ScreenClips.traversingChat = true;
        try {
            original.call(self, node, consumer);
        } finally {
            ScreenClips.traversingChat = false;
        }
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void flashclips$reset(CallbackInfo ci) {
        ScreenClips.chatStratum = -1;
    }
}
