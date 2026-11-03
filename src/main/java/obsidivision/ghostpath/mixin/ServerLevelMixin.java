package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    /** 26.2 ServerLevel.java:217; a set, so an equal event queued twice runs once. */
    @Shadow
    @Final
    private ObjectLinkedOpenHashSet<BlockEventData> blockEvents;

    /**
     * 26.2 ServerLevel.java:992 {@code private boolean addEntity(Entity)}. addFreshEntity (:957), addWithUUID (:961)
     * and the passenger variants all end here; chunk-loaded entities do not (they go through the entity manager).
     * RETURN: only entities that were really added.
     */
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void ghostpath$afterAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        UndoLog log = UndoLog.active();
        if (log != null && cir.getReturnValueZ()) {
            log.onEntityAdded(entity);
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && cir.getReturnValueZ()) {
            tracker.onEntityTouched(entity, "spawned");
        }
    }

    /** 26.2 ServerLevel.java:1247 {@code public void blockEvent(BlockPos, Block, int, int)}: queues a BlockEventData (:1248). */
    @Inject(method = "blockEvent", at = @At("HEAD"))
    private void ghostpath$beforeBlockEvent(BlockPos pos, Block block, int b0, int b1, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            BlockEventData data = new BlockEventData(pos, block, b0, b1);
            tracker.onBlockEventQueued((ServerLevel) (Object) this, data, this.blockEvents.contains(data));
        }
    }

    /** 26.2 ServerLevel.java:1278 {@code private boolean doBlockEvent(BlockEventData)}, called from runBlockEvents (:1258). */
    @WrapMethod(method = "doBlockEvent")
    private boolean ghostpath$chainBlockEvent(BlockEventData data, Operation<Boolean> original) {
        CauseTracker tracker = CauseTracker.active();
        boolean[] result = new boolean[1];
        CauseTracker.within(tracker, tracker != null && tracker.onBlockEventRun(data), () -> result[0] = original.call(data));
        return result[0];
    }

    /** 26.2 ServerLevel.java:822 {@code public void tickNonPassenger(Entity)}: one entity's tick, then its passengers'. */
    @WrapMethod(method = "tickNonPassenger")
    private void ghostpath$chainEntityTick(Entity entity, Operation<Void> original) {
        CauseTracker tracker = CauseTracker.active();
        boolean chain = tracker != null && tracker.isChainEntity(entity);
        CauseTracker.within(tracker, chain, () -> original.call(entity));
        if (chain) {
            tracker.onChainEntityTicked(entity);
        }
    }

    /** 26.2 ServerLevel.java:836 {@code private void tickPassenger(Entity vehicle, Entity entity)}. */
    @WrapMethod(method = "tickPassenger")
    private void ghostpath$chainPassengerTick(Entity vehicle, Entity entity, Operation<Void> original) {
        CauseTracker tracker = CauseTracker.active();
        boolean chain = tracker != null && tracker.isChainEntity(entity);
        CauseTracker.within(tracker, chain, () -> original.call(vehicle, entity));
        if (chain) {
            tracker.onChainEntityTicked(entity);
        }
    }
}
