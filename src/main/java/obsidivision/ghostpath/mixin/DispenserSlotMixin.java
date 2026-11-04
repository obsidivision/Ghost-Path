package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Seeded slot choice (ruling): 26.2 {@code DispenserBlock.dispenseFrom} (DispenserBlock.java:92-98) and its override
 * {@code DropperBlock.dispenseFrom} (DropperBlock.java:47-54) both call {@code blockEntity.getRandomSlot(level.getRandom())},
 * which draws {@code nextInt} once per filled slot (DispenserBlockEntity.java:34-45, the draw at :40). During chain work
 * the argument is the seeded chain stream instead.
 */
@Mixin({DispenserBlock.class, DropperBlock.class})
public abstract class DispenserSlotMixin {
    @ModifyArg(method = "dispenseFrom", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;getRandomSlot(Lnet/minecraft/util/RandomSource;)I"))
    private RandomSource ghostpath$seededSlot(RandomSource random, @Local(argsOnly = true) ServerLevel level) {
        return CauseTracker.randomFor(level, random);
    }
}
