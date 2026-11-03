package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {
    /** 26.2 LevelTicks.java:34. */
    @Shadow
    @Final
    private Long2ObjectMap<LevelChunkTicks<T>> allContainers;

    /**
     * 26.2 LevelTicks.java:182-192 {@code private void runCollectedTicks(BiConsumer<BlockPos, T>)}: polls each
     * {@code ScheduledTick entry} and calls {@code output.accept(entry.pos(), entry.type())} (:190), which is
     * ServerLevel.tickBlock / tickFluid (ServerLevel.java:391,393). A chain tick (by identity) runs with chain context on.
     */
    @WrapOperation(method = "runCollectedTicks", at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void ghostpath$runTick(BiConsumer<Object, Object> output, Object pos, Object type, Operation<Void> original,
                                   @Local ScheduledTick<?> entry) {
        CauseTracker tracker = CauseTracker.active();
        boolean chain = tracker != null && tracker.onTickRun(entry);
        CauseTracker.within(tracker, chain, () -> original.call(output, pos, type));
    }

    /**
     * 26.2 LevelTicks.java:236-252 {@code clearArea(BoundingBox)}: the predicate {@code tickInsideBB} (:237) removes ticks
     * from every container (:240, LevelChunkTicks.removeIf :71-80) and from alreadyRunThisTick/toRunThisTick (:250-251)
     * without running them. Wrapped so each removed chain tick leaves the chain set. (In vanilla only the game-test
     * framework calls clearArea: StructureUtils.java:73, GameTestInfo.java:77.)
     */
    @ModifyVariable(method = "clearArea", at = @At("STORE"), ordinal = 0)
    private Predicate<ScheduledTick<T>> ghostpath$watchClear(Predicate<ScheduledTick<T>> inside) {
        return tick -> {
            boolean removed = inside.test(tick);
            CauseTracker tracker = CauseTracker.active();
            if (removed && tracker != null) {
                tracker.onTickRemoved(tick, "cleared");
            }
            return removed;
        };
    }

    /**
     * 26.2 LevelTicks.java:61-68 {@code removeContainer(ChunkPos)}: the chunk unloads; its ticks are saved and come back
     * as new objects on reload (LevelChunkTicks.unpack, :109-114), so a chain tick cannot be followed across it.
     */
    @Inject(method = "removeContainer", at = @At("HEAD"))
    private void ghostpath$beforeRemoveContainer(ChunkPos pos, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        LevelChunkTicks<T> container = this.allContainers.get(pos.pack());
        if (tracker != null && container != null) {
            container.getAll().toList().forEach(t -> tracker.onTickRemoved(t, "saved with unloading chunk: not followed"));
        }
    }
}
