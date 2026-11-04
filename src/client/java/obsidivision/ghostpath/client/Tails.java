package obsidivision.ghostpath.client;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.preview.PreviewSim;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side continuation (build step 5) of projectiles that were still flying when the ghost run stopped at the tick
 * limit. For each such line of the latest snapshot there is one {@link Job}: a copy of the entity made from its saved state
 * ({@link PathSnapshot.Tail}), <b>never added to the level</b>, ticked with the vanilla {@code tick()} of 26.2 (on the client
 * level, so against the client's blocks) until it hits something, is removed, leaves the loaded chunks, or has flown
 * {@value #MAX_TICKS} ticks. Hit effects, particles and sounds are off ({@link PreviewSim}, client mixins, and
 * {@code ProjectileHitMixin}). The work is spread over client ticks ({@link #BUDGET_NANOS} per tick); the renderer draws
 * what is done so far. A new snapshot (another serial) or another client level starts everything over.
 */
public final class Tails {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    /** Ticks a continuation may run. */
    static final int MAX_TICKS = 6000;
    /** Client-thread time per client tick for continuations. */
    static final long BUDGET_NANOS = 3_000_000L;

    /** One continuation. Client thread only. */
    public static final class Job {
        final PathSnapshot.Line line;
        final PathSnapshot.Tail tail;
        /** Tick (relative to the recording) of the first continued point is {@code line.end().tick() + 1}. */
        public final LongArrayList ticks = new LongArrayList();
        public final DoubleArrayList xyz = new DoubleArrayList();
        private @Nullable Entity copy;
        private int run;
        private @Nullable String endReason;

        Job(PathSnapshot.Line line, PathSnapshot.Tail tail) {
            this.line = line;
            this.tail = tail;
        }

        public boolean done() {
            return this.endReason != null;
        }

        /** Why the continuation ended; null while it still runs. */
        public @Nullable String endReason() {
            return this.endReason;
        }

        public int points() {
            return this.ticks.size();
        }

        void step(ClientLevel level) {
            if (this.endReason != null) {
                return;
            }
            Entity e = this.copy;
            if (e == null) {
                e = this.create(level);
                if (e == null) {
                    return;
                }
                this.copy = e;
            }
            HitResult.Type hit;
            PreviewSim.begin(e);
            try {
                e.tick();
            } catch (Throwable t) {
                LOGGER.warn("[ghostpath] continuing {} failed after {} ticks", this.tail.type(), this.run, t);
                this.endReason = "failed";
                return;
            } finally {
                hit = PreviewSim.end();
            }
            this.run++;
            Vec3 p = e.position();
            this.ticks.add(this.line.end().tick() + this.run);
            this.xyz.add(p.x);
            this.xyz.add(p.y);
            this.xyz.add(p.z);
            if (hit != null) {
                // an arrow that hits a block stays in it (AbstractArrow.onHitBlock sets inGround, :542; the hit effects are off here):
                // the server's run calls that "stuck"
                this.endReason = hit == HitResult.Type.BLOCK ? (e instanceof AbstractArrow ? "stuck" : "hit block") : "hit entity";
            } else if (e.isRemoved()) {
                this.endReason = "removed";
            } else if (p.y < level.getMinY() - 64) {
                this.endReason = "out of world";
            } else if (e.portalProcess != null) {
                this.endReason = "portal";
            } else if (!level.hasChunkAt(e.blockPosition())) {
                this.endReason = "unloaded";
            } else if (this.run >= MAX_TICKS) {
                this.endReason = "limit";
            }
            if (this.endReason != null) {
                this.copy = null; // it never was in the level; just let it go
            }
        }

        private @Nullable Entity create(ClientLevel level) {
            try {
                CompoundTag nbt = this.tail.nbt().copy();
                nbt.putString("id", this.tail.type());
                Optional<Entity> made = EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt), level,
                    new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
                Entity e = made.orElse(null);
                if (e == null) {
                    this.endReason = "failed";
                    return null;
                }
                // what the save does not hold
                e.tickCount = this.tail.tickCount();
                e.xo = this.tail.xo();
                e.yo = this.tail.yo();
                e.zo = this.tail.zo();
                e.xOld = e.xo;
                e.yOld = e.yo;
                e.zOld = e.zo;
                e.setOnGround(this.tail.onGround());
                return e;
            } catch (Throwable t) {
                LOGGER.warn("[ghostpath] could not make a copy of {} to continue it", this.tail.type(), t);
                this.endReason = "failed";
                return null;
            }
        }
    }

    private static long serial = -1;
    private static @Nullable ClientLevel forLevel;
    private static final Map<Integer, Job> JOBS = new HashMap<>();

    private Tails() {
    }

    /** The continuation of line {@code index} of the snapshot, or null (no tail, or not started for this snapshot). */
    public static @Nullable Job job(PathSnapshot snapshot, int index) {
        return snapshot.serial() == serial ? JOBS.get(index) : null;
    }

    /** End of every client tick: (re)creates the jobs for a new snapshot and runs them for a few milliseconds. */
    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        PathSnapshot s = PathStore.latest();
        if (level == null || s == null) {
            if (!JOBS.isEmpty()) {
                JOBS.clear();
                serial = -1;
            }
            return;
        }
        if (s.serial() != serial || level != forLevel) {
            JOBS.clear();
            serial = s.serial();
            forLevel = level;
            List<PathSnapshot.Line> lines = s.lines();
            for (int i = 0; i < lines.size(); i++) {
                PathSnapshot.Line l = lines.get(i);
                if (l.tail() != null) {
                    JOBS.put(i, new Job(l, l.tail()));
                }
            }
        }
        if (JOBS.isEmpty() || !level.dimension().identifier().toString().equals(s.dimension())) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        for (Job j : JOBS.values()) {
            while (!j.done() && System.nanoTime() < deadline) {
                j.step(level);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }
}
