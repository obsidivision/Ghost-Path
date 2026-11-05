package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 Projectile.java:46-48 {@code leftOwner, leftOwnerChecked, hasBeenShot}. {@code leftOwner} and {@code hasBeenShot}
 * are saved (:79-93) but {@code leftOwnerChecked} is not (it gates checkLeftOwner, :111-116).
 */
@Mixin(Projectile.class)
public interface GhostProjectileAccessor {
    @Accessor("leftOwnerChecked")
    boolean ghostpath$leftOwnerChecked();

    @Accessor("leftOwnerChecked")
    void ghostpath$setLeftOwnerChecked(boolean value);

    /** 26.2 Projectile.java:45 {@code protected EntityReference<Entity> owner}: the owner's UUID, also when the owner is not in this level (the ghost has no players). */
    @Accessor("owner")
    net.minecraft.world.entity.@org.jspecify.annotations.Nullable EntityReference<net.minecraft.world.entity.Entity> ghostpath$owner();
}
