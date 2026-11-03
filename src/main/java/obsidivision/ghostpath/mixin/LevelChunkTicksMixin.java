package obsidivision.ghostpath.mixin;

import java.util.Set;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin {
    /** 26.2 LevelChunkTicks.java:21, keyed by (type, pos) via ScheduledTick.UNIQUE_TICK_HASH (ScheduledTick.java:22-34). */
    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    /**
     * 26.2 LevelChunkTicks.java:53 {@code public void schedule(ScheduledTick<T>)}: adds only if no (type, pos) tick is
     * pending (:54). Every scheduled block/fluid tick ends here (LevelTicks.schedule, LevelTicks.java:71-79). HEAD, so we
     * can tell whether ours will be dropped as a duplicate; if so the pending tick joins the chain instead.
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    private void ghostpath$beforeSchedule(ScheduledTick<?> tick, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onTickScheduled(tick, this.ticksPerPosition.contains(tick));
        }
    }
}
