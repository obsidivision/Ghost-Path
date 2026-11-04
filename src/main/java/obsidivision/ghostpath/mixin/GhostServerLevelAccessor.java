package obsidivision.ghostpath.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 ServerLevel.java:202 {@code entityTickList} (entities tick in its order, :425) and :205 {@code entityManager}
 * (its tick() finishes entity-section loads, :445, PersistentEntitySectionManager.java:240).
 */
@Mixin(ServerLevel.class)
public interface GhostServerLevelAccessor {
    @Accessor("entityTickList")
    EntityTickList ghostpath$entityTickList();

    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> ghostpath$entityManager();
}
