package obsidivision.ghostpath.record;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Stands in for {@code Level.random} (26.2 Level.java:124) and for each entity's own {@code Entity.random}
 * (Entity.java:254); both are {@code RandomSource.create()}, unseeded. Every call is passed straight to the wrapped
 * random, so behaviour and draw order are unchanged. When chain work is running in this level, a draw from an
 * <b>unseeded</b> wrapper is reported to the {@link CauseTracker} (DESIGN ruling R1). A <b>seeded</b> wrapper (the
 * own random of an entity built by chain work, seeded from the chain stream) reports nothing. Catches both field reads
 * and {@code level.getRandom()} (Level.java:1050 returns the field).
 */
public final class WatchedRandom implements RandomSource {
    private final RandomSource delegate;
    private final Level level;
    private final String source;
    private final boolean seeded;
    private final @org.jspecify.annotations.Nullable Entity owner;

    /** @param source "Level.random" or "Entity.random", for the event log */
    public WatchedRandom(RandomSource delegate, Level level, String source, boolean seeded) {
        this(delegate, level, source, seeded, null);
    }

    /** @param owner the entity whose own random this is (null for a level's) */
    public WatchedRandom(RandomSource delegate, Level level, String source, boolean seeded, @org.jspecify.annotations.Nullable Entity owner) {
        this.owner = owner;
        this.delegate = delegate;
        this.level = level;
        this.source = source;
        this.seeded = seeded;
    }

    /** The wrapped random (its state is what a ghost copy continues from). */
    public RandomSource delegate() {
        return this.delegate;
    }

    public boolean seeded() {
        return this.seeded;
    }

    private void draw(String method) {
        if (this.seeded) {
            return;
        }
        CauseTracker t = CauseTracker.active();
        if (t != null && t.inChain(this.level)) {
            t.onUnseededDraw(this.source, method, this.owner);
        }
    }

    @Override
    public RandomSource fork() {
        this.draw("fork");
        return this.delegate.fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        this.draw("forkPositional");
        return this.delegate.forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        this.draw("setSeed");
        this.delegate.setSeed(seed);
    }

    @Override
    public int nextInt() {
        this.draw("nextInt");
        return this.delegate.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        this.draw("nextInt");
        return this.delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
        this.draw("nextLong");
        return this.delegate.nextLong();
    }

    @Override
    public boolean nextBoolean() {
        this.draw("nextBoolean");
        return this.delegate.nextBoolean();
    }

    @Override
    public float nextFloat() {
        this.draw("nextFloat");
        return this.delegate.nextFloat();
    }

    @Override
    public double nextDouble() {
        this.draw("nextDouble");
        return this.delegate.nextDouble();
    }

    @Override
    public void consumeCount(int rounds) {
        this.draw("consumeCount");
        this.delegate.consumeCount(rounds);
    }

    @Override
    public double nextGaussian() {
        this.draw("nextGaussian");
        return this.delegate.nextGaussian();
    }
}
