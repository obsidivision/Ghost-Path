package obsidivision.ghostpath.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 ServerLevel.java:217 {@code blockEvents}: the queued block events (a set, in queue order). */
@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {
    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> ghostpath$blockEvents();
}
