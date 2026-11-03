package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.BiConsumer;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin {
    /**
     * 26.2 LevelTicks.java:182-192 {@code private void runCollectedTicks(BiConsumer<BlockPos, T>)}: polls each
     * {@code ScheduledTick entry} and calls {@code output.accept(entry.pos(), entry.type())} (:190), which is
     * ServerLevel.tickBlock / tickFluid (ServerLevel.java:391,393). A chain tick runs with chain context on.
     */
    @WrapOperation(method = "runCollectedTicks", at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void ghostpath$runTick(BiConsumer<Object, Object> output, Object pos, Object type, Operation<Void> original,
                                   @Local ScheduledTick<?> entry) {
        CauseTracker tracker = CauseTracker.active();
        boolean chain = tracker != null && tracker.onTickRun(entry);
        CauseTracker.within(tracker, chain, () -> original.call(output, pos, type));
    }
}
