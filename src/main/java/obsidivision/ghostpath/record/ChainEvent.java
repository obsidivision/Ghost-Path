package obsidivision.ghostpath.record;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * One piece of chain work, in the order it happened.
 *
 * @param tick   game ticks since the recording began (not absolute, so two runs can be compared)
 * @param kind   what happened
 * @param pos    the block position involved, if any
 * @param detail everything else, as text that is stable across runs (no UUIDs, no absolute game time)
 */
public record ChainEvent(long tick, Kind kind, @Nullable BlockPos pos, String detail) {
    public enum Kind {
        /** a block state or block entity changed (destroyed blocks included) */
        WRITE,
        /** a scheduled tick was queued by chain work; "merged" if an equal (type, pos) tick was already pending */
        TICK_SCHEDULED,
        /** a chain scheduled tick ran */
        TICK_RUN,
        /** a block event was queued by chain work; "merged" if an equal one was already queued */
        BLOCK_EVENT_QUEUED,
        /** a chain block event ran */
        BLOCK_EVENT_RUN,
        /** an entity joined the chain (spawned, moved, pushed or hurt by chain work) */
        ENTITY_TAGGED,
        /** a chain entity's position and velocity after its tick (exact doubles) */
        ENTITY_STATE,
        /** a block entity joined the chain (created or changed by chain work) */
        BLOCK_ENTITY_TAGGED,
        /** chain work drew from the level's own, unseeded random; detail is the call site (R1) */
        UNSEEDED_DRAW
    }

    @Override
    public String toString() {
        return "t+" + this.tick + " " + this.kind + (this.pos == null ? "" : " " + this.pos.toShortString()) + " " + this.detail;
    }
}
