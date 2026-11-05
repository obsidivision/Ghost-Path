package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 PersistentEntitySectionManager.java:36 {@code permanentStorage}: the level's entity storage (an {@code EntityStorage} on a server level). */
@Mixin(PersistentEntitySectionManager.class)
public interface EntitySectionManagerAccessor {
    @Accessor("permanentStorage")
    EntityPersistentStorage<Entity> ghostpath$permanentStorage();
}
