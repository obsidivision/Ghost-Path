package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BooleanSupplier;
import net.minecraft.util.thread.BlockableEventLoop;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {
    /**
     * 26.2 BlockableEventLoop.java:139 {@code public void managedBlock(BooleanSupplier condition)}: polls queued tasks
     * ({@code pollTask} :124, {@code doRunTask} :158) until the condition holds. The server thread blocks here when chain
     * work reads a chunk that is not loaded yet (ServerChunkCache.getChunk :166, getChunkFuture :219; the chunk cache's
     * {@code pollTask} also runs distance-manager updates, :628-634). The tasks it runs meanwhile are not chain work
     * ({@link CauseTracker#outsideChain}); the chain read itself carries on with context on afterwards. A call whose
     * condition already holds runs no task; it is passed through (most calls are like that: a chunk-cache miss whose
     * future is done).
     */
    @WrapMethod(method = "managedBlock")
    private void ghostpath$notChainWhileBlocked(BooleanSupplier condition, Operation<Void> original) {
        if (condition.getAsBoolean()) {
            original.call(condition); // nothing to wait for: the loop polls no task (:142)
            return;
        }
        CauseTracker.outsideChain(() -> original.call(condition));
    }
}
