package obsidivision.ghostpath.mixin;

import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 Level.java:111-113 {@code blockEntityTickers} and {@code pendingBlockEntityTickers} (block entities tick in
 * this list order, :533-539), and Level.java:135 {@code subTickCount} (the next sub-tick order handed to a scheduled
 * tick, :1088-1089).
 */
@Mixin(Level.class)
public interface GhostLevelAccessor {
    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> ghostpath$blockEntityTickers();

    @Accessor("pendingBlockEntityTickers")
    List<TickingBlockEntity> ghostpath$pendingBlockEntityTickers();

    @Accessor("subTickCount")
    long ghostpath$subTickCount();

    @Accessor("subTickCount")
    void ghostpath$setSubTickCount(long value);
}
