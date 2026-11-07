package obsidivision.ghostpath.record;

import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Bounded repetition evidence for consecutive per-tick fingerprints. Requires three complete periods and at least four
 * repeated samples, rather than a matching prefix. A later mismatch revokes the result. Hash equality is evidence of
 * repetition, not proof that an incompletely modelled machine will repeat forever; never use this alone to stop a run.
 */
public final class CycleDetector {
    private static final int MAX_TICKS = 20_000;
    private final long[] hashes = new long[MAX_TICKS];
    private final int[] matches = new int[MAX_TICKS / 3 + 1];
    private long lastTick;
    private boolean hasLastTick;
    private long samples;
    private int detectedPeriod;
    private long detectedFirstRepeatTick;

    public void add(long tick, long stateHash) {
        if (hasLastTick && (lastTick == Long.MAX_VALUE || tick != lastTick + 1)) {
            throw new IllegalArgumentException("ticks must be consecutive");
        }
        lastTick = tick;
        hasLastTick = true;
        int retained = (int) Math.min(samples, MAX_TICKS - 1);
        detectedPeriod = 0;
        for (int p = 1; p < matches.length && p <= retained; p++) {
            int slot = (int) Math.floorMod(samples - p, (long) MAX_TICKS);
            matches[p] = hashes[slot] == stateHash ? Math.min(matches[p] + 1, MAX_TICKS - p) : 0;
            if (detectedPeriod == 0 && matches[p] >= Math.max(4, 2 * p)) {
                detectedPeriod = p;
                detectedFirstRepeatTick = tick - matches[p] + 1;
            }
        }
        hashes[(int) (samples % MAX_TICKS)] = stateHash;
        samples++;
    }

    public OptionalInt period() {
        return detectedPeriod == 0 ? OptionalInt.empty() : OptionalInt.of(detectedPeriod);
    }

    /** Start of the second occurrence in the currently matching suffix. */
    public OptionalLong firstRepeatTick() {
        return detectedPeriod == 0 ? OptionalLong.empty() : OptionalLong.of(detectedFirstRepeatTick);
    }
}
