package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.loot.LootContext;
import obsidivision.ghostpath.record.CauseTracker;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2 LootContext.java:138-143 {@code Builder.create(Optional<Identifier> randomSequenceKey)}: {@code Optional.ofNullable(this.random).or(() ->
 * randomSequenceKey.map(server::getRandomSequence)).orElseGet(level::getRandom)}. Block drops, mob loot and unseeded chests roll on the
 * server's per-table random sequence (shared by the real level and the ghosts, saved with the world) or on the level's random; neither
 * is the chain's. During chain work the result is replaced by a seeded stream ({@link CauseTracker#lootRandomFor}), unless the builder
 * carries its own random (a chest with a loot seed), which is deterministic already.
 */
@Mixin(LootContext.Builder.class)
public abstract class LootContextBuilderMixin {
    @Shadow
    private @Nullable RandomSource random;

    @ModifyExpressionValue(method = "create", at = @At(value = "INVOKE", target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
    private Object ghostpath$lootRandom(Object original, @Local(argsOnly = true) Optional<Identifier> randomSequenceKey) {
        if (this.random != null) {
            return original;
        }
        return CauseTracker.lootRandomFor(((LootContext.Builder) (Object) this).getLevel(), randomSequenceKey.orElse(null), (RandomSource) original);
    }
}
