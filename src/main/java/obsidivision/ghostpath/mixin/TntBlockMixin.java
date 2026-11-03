package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.TntBlock;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(TntBlock.class)
public abstract class TntBlockMixin {
    /**
     * 26.2 TntBlock.java:76-79 {@code wasExploded(ServerLevel, BlockPos, Explosion)}: the short fuse,
     * {@code PrimedTnt.getRandomShortFuse(fuse, level.getRandom())} at :78. Chain TNT draws it from the seeded chain
     * random (DESIGN R1).
     */
    @ModifyExpressionValue(method = "wasExploded", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getRandom()Lnet/minecraft/util/RandomSource;"))
    private RandomSource ghostpath$fuseRandom(RandomSource original, @Local(argsOnly = true) ServerLevel level) {
        return CauseTracker.randomFor(level, original);
    }
}
