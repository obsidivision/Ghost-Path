package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
            tracker.onEntityAdded(entity);
        }
    }

    /**
     * 26.2 ServerLevel.java:1247-1249 {@code blockEvent(BlockPos, Block, int, int)}: {@code blockEvents.add(new
     * BlockEventData(...))}. The queue is a set: if an equal event is queued already, ours is dropped and the queued
     * object joins the chain instead. Tracked by identity of the queued object.
     */
    @WrapOperation(method = "blockEvent", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z"))
    private boolean ghostpath$queueBlockEvent(ObjectLinkedOpenHashSet<Object> queue, Object event, Operation<Boolean> original) {
        boolean added = original.call(queue, event);
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && tracker.inChain((ServerLevel) (Object) this)) {
            BlockEventData queued = (BlockEventData) event;
            if (!added) {
                for (BlockEventData existing : this.blockEvents) {
                    if (existing.equals(event)) {
                        queued = existing;
                        break;
                    }
                }
            }
            tracker.onBlockEventQueued((ServerLevel) (Object) this, queued, !added);
        }
        return added;
    }

    /**
     * 26.2 ServerLevel.java:1665-1667 {@code clearBlockEvents(BoundingBox)} ({@code @VisibleForTesting}; used by the
     * game-test framework, StructureUtils.java:74, GameTestInfo.java:78): removes queued events without running them.
     */
    @ModifyArg(method = "clearBlockEvents", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;removeIf(Ljava/util/function/Predicate;)Z"))
    private Predicate<BlockEventData> ghostpath$watchClearBlockEvents(Predicate<BlockEventData> inside) {
        return event -> {
            boolean removed = inside.test(event);
            CauseTracker tracker = CauseTracker.active();
            if (removed && tracker != null) {
                tracker.onBlockEventRemoved(event);
            }
            return removed;
        };
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
