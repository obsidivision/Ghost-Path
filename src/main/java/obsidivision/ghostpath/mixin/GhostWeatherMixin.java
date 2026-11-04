package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
public abstract class GhostWeatherMixin {
    /**
     * 26.2 ServerLevel.java:784-790 in {@code advanceWeatherCycle}: when the rain starts or stops, START/STOP_RAINING and
     * the rain/thunder levels go to every player on the server ({@code broadcastAll(Packet)}, no dimension). A ghost's
     * weather is its own copy and must not reach real players.
     */
    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private boolean ghostpath$noGhostWeatherPackets(PlayerList players, Packet<?> packet) {
        return !((Object) this instanceof GhostLevel);
    }
}
