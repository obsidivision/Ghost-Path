package obsidivision.ghostpath.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionSendMixin {
    /**
     * 26.2 Connection.java:280 {@code public void send(Packet, ChannelFutureListener, boolean flush)}: the one method
     * every send goes through (the other two overloads, :272 and :276, call it; ServerCommonPacketListenerImpl.send,
     * :161, calls it too). Counts packets sent from the server thread while ghost work runs: there must be none
     * (step 3, ruling 4: ghost entities share ids with real ones, so none may ever reach a client).
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void ghostpath$watchPackets(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        GhostLevel.onPacketSent(packet);
    }
}
