package obsidivision.ghostpath.record;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Stands in for {@code Level.random} (26.2 Level.java:124, {@code RandomSource.create()}, unseeded). Every call is passed
 * straight to the original random, so behaviour and draw order are unchanged; when chain work is running in this level
 * the draw is also reported to the {@link CauseTracker} as an unseeded draw (DESIGN ruling R1). Catches both
 * {@code level.random} field reads and {@code level.getRandom()} (Level.java:1050 returns the field).
 */
public final class WatchedRandom implements RandomSource {
    private final RandomSource delegate;
    private final Level level;

    public WatchedRandom(RandomSource delegate, Level level) {
        this.delegate = delegate;
        this.level = level;
    }

    private void draw(String method) {
        CauseTracker t = CauseTracker.active();
        if (t != null && t.inChain(this.level)) {
            t.onUnseededDraw(method);
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
