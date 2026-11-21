package dev.flashclips.mixin;

import dev.flashclips.KillTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    /** Entity event 35 = totem of undying used. Only reached on the main thread. */
    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void flashclips$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet.getEventId() != 35) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.isSameThread()) return;
        Entity entity = packet.getEntity(mc.level);
        if (entity != null) KillTracker.onTotemPop(entity);
    }
}
