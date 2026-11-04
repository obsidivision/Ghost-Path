package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Item drop positions (and face-drop velocities) from blocks: seeded during chain work (1b ruling on item drops). */
@Mixin(Block.class)
public abstract class BlockDropMixin {
    private static final String GET_RANDOM = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;";

    /** 26.2 Block.java:406-413 {@code popResource(Level, BlockPos, ItemStack)}: {@code level.getRandom()} at :408, jitter :409-411. */
    @ModifyExpressionValue(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "INVOKE", target = GET_RANDOM))
    private static RandomSource ghostpath$popRandom(RandomSource original, @Local(argsOnly = true) Level level) {
        return CauseTracker.randomFor(level, original);
    }

    /** 26.2 Block.java:415-428 {@code popResourceFromFace}: {@code level.getRandom()} at :421, position and velocity :422-427. */
    @ModifyExpressionValue(method = "popResourceFromFace", at = @At(value = "INVOKE", target = GET_RANDOM))
    private static RandomSource ghostpath$popFaceRandom(RandomSource original, @Local(argsOnly = true) Level level) {
        return CauseTracker.randomFor(level, original);
    }
}
