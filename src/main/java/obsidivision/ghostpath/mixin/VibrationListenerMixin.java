package obsidivision.ghostpath.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 VibrationSystem.java:192 {@code class Listener implements GameEventListener}, field {@code system} (:193): the
 * block entity (SculkSensorBlockEntity, its CalibratedSculkSensorBlockEntity subclass, SculkShriekerBlockEntity) or the
 * entity (Warden, Allay) that owns the listener.
 */
@Mixin(VibrationSystem.Listener.class)
public abstract class VibrationListenerMixin {
    @Shadow
    @Final
    private VibrationSystem system;

    /**
     * 26.2 VibrationSystem.java:247-251 {@code private void scheduleVibration(ServerLevel, Data, Holder<GameEvent>,
     * GameEvent.Context, Vec3 origin, Vec3 dest)}: adds the candidate that the owner's ticker (:274-291) later selects
     * and receives. Both {@code handleGameEvent} (:235) and {@code forceScheduleVibration} (:244) end here. Game events
     * are delivered synchronously (GameEventDispatcher.post, GameEventDispatcher.java:22-61), so a chain game event
     * reaches this in chain context.
     */
    @Inject(method = "scheduleVibration", at = @At("HEAD"))
    private void ghostpath$beforeScheduleVibration(ServerLevel level, VibrationSystem.Data data, Holder<GameEvent> event, GameEvent.Context context,
                                                   Vec3 origin, Vec3 dest, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onVibrationScheduled(level, this.system);
        }
    }
}
