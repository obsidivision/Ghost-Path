package obsidivision.ghostpath.mixin;

import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin<T> {
    /** 26.2 LevelChunkTicks.java: public {@code hasScheduledTick(BlockPos, T)} and {@code getAll()}. */
    @Shadow
    public abstract boolean hasScheduledTick(BlockPos pos, T type);

    @Shadow
    public abstract Stream<ScheduledTick<T>> getAll();

    /**
     * 26.2 LevelChunkTicks.java:53 {@code public void schedule(ScheduledTick<T>)}: adds only if no (type, pos) tick is
     * pending (:54). Every scheduled block/fluid tick ends here (LevelTicks.schedule, LevelTicks.java:71-79).
     * Only public methods are used, no fields: Lithium (tick_scheduler) {@code @Overwrite}s schedule, peek, poll,
     * hasScheduledTick, getAll, count, ... and nulls {@code ticksPerPosition} and {@code tickQueue}, so neither a field
     * shadow nor an INVOKE of {@code scheduleUnchecked} exists there (crash at world start, seen with Lithium 0.25.3).
     * HEAD, so we can tell whether ours will be dropped as a duplicate; if so the pending tick object joins the chain
     * instead, if not the tick will be accepted (the check is the whole decision in both implementations).
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    private void ghostpath$beforeSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!this.hasScheduledTick(tick.pos(), tick.type())) {
            UndoLog log = UndoLog.active();
            if (log != null) {
                log.onTickScheduled(tick);
            }
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker == null || !tracker.inChainAnyLevel()) {
            return;
        }
        ScheduledTick<?> pending = null;
        boolean unreachable = false;
        if (this.hasScheduledTick(tick.pos(), tick.type())) {
            pending = this.getAll().filter(q -> q.type() == tick.type() && q.pos().equals(tick.pos())).findFirst().orElse(null);
            unreachable = pending == null; // only in pendingTicks (saved form), no object to follow
        }
        tracker.onTickScheduled(tick, pending, unreachable);
    }
}
