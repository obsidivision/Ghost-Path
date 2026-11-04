package obsidivision.ghostpath.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntity;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
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
     *
     * <p>Undo: {@code addCandidate} puts the vibration into the owner's {@code VibrationSystem.Data} (saved in its NBT as
     * {@code listener.selector}) without any hooked call (no {@code getBlockEntity}, no {@code setChanged}), so the
     * block entity's first-touch snapshot (UndoLog) would be taken at its next tick, already holding the chain's vibration,
     * and the restore would put that pending vibration back: the real sensor would activate after the recording (found by
     * the step 3 ghost check: the copy of the restored sensor had the lever's vibration pending). So the snapshot is taken
     * here, before the candidate is added.
     */
    @Inject(method = "scheduleVibration", at = @At("HEAD"))
    private void ghostpath$beforeScheduleVibration(ServerLevel level, VibrationSystem.Data data, Holder<GameEvent> event, GameEvent.Context context,
                                                   Vec3 origin, Vec3 dest, CallbackInfo ci) {
        UndoLog undo = UndoLog.active();
        if (undo != null && this.system instanceof BlockEntity blockEntity) {
            undo.onBlockEntityAccess(blockEntity);
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onVibrationScheduled(level, this.system);
        }
    }
}
