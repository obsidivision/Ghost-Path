package obsidivision.ghostpath.mixin;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.record.CauseTracker;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin<T> {
    /** 26.2 LevelChunkTicks.java:21, keyed by (type, pos) via ScheduledTick.UNIQUE_TICK_HASH (ScheduledTick.java:22-34). */
    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    /** 26.2 LevelChunkTicks.java:19, the queued tick objects. */
    @Shadow
    @Final
    private Queue<ScheduledTick<T>> tickQueue;

    /** 26.2 LevelChunkTicks.java:20, saved ticks not unpacked yet (only probes are in ticksPerPosition, :27-32). */
    @Shadow
    private @Nullable List<SavedTick<T>> pendingTicks;

    /**
     * 26.2 LevelChunkTicks.java:53 {@code public void schedule(ScheduledTick<T>)}: adds only if no (type, pos) tick is
     * pending (:54). Every scheduled block/fluid tick ends here (LevelTicks.schedule, LevelTicks.java:71-79). HEAD, so we
     * can tell whether ours will be dropped as a duplicate; if so the pending tick object joins the chain instead.
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    private void ghostpath$beforeSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker == null || !tracker.inChainAnyLevel()) {
            return;
        }
        ScheduledTick<?> pending = null;
        boolean unreachable = false;
        if (this.ticksPerPosition.contains(tick)) {
            for (ScheduledTick<T> queued : this.tickQueue) {
                if (queued.type() == tick.type() && queued.pos().equals(tick.pos())) {
                    pending = queued;
                    break;
                }
            }
            unreachable = pending == null; // only in pendingTicks (saved form), no object to follow
        }
        tracker.onTickScheduled(tick, pending, unreachable);
    }
}
