package obsidivision.ghostpath.mixin;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity areas of the footprint (ruling after 1c): every box that chain work queries for entities. All lookups by box end
 * in these three {@code Level} methods (26.2): {@code EntityGetter.getEntities(except, bb)}, {@code getEntitiesOfClass},
 * {@code getEntityCollisions}, {@code getPushableEntities}, explosion knockback and the typed overloads call them.
 * {@code ServerLevel} overrides none of the three.
 */
@Mixin(Level.class)
public abstract class LevelEntityAreaMixin {
    /** 26.2 Level.java:775 {@code public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector)}. */
    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void ghostpath$areaExcept(Entity except, AABB bb, Predicate<Entity> selector, CallbackInfoReturnable<List<Entity>> cir) {
        record((Level) (Object) this, bb);
    }

    /** 26.2 Level.java:806 {@code public <T extends Entity> void getEntities(EntityTypeTest, AABB, Predicate, List, int maxResults)}. */
    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V", at = @At("HEAD"))
    private <T extends Entity> void ghostpath$areaTyped(EntityTypeTest<Entity, T> type, AABB bb, Predicate<? super T> selector, List<? super T> output, int max, CallbackInfo ci) {
        record((Level) (Object) this, bb);
    }

    /** 26.2 Level.java:834 {@code public <T extends Entity> boolean hasEntities(EntityTypeTest, AABB, Predicate)}. */
    @Inject(method = "hasEntities", at = @At("HEAD"))
    private <T extends Entity> void ghostpath$areaHas(EntityTypeTest<Entity, T> type, AABB bb, Predicate<? super T> selector, CallbackInfoReturnable<Boolean> cir) {
        record((Level) (Object) this, bb);
    }

    private static void record(Level level, AABB bb) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onEntityArea(level, bb);
        }
    }
}
