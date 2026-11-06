package obsidivision.ghostpath.record;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

public final class CycleDetector {
    private static final int MAX_TICKS = 20_000;
    private static final int CONFIRMATION_TICKS = 4;

    private final long[] recentHashes = new long[CONFIRMATION_TICKS];
    private final long[] recentTicks = new long[CONFIRMATION_TICKS];
    private final ArrayDeque<Window> windows = new ArrayDeque<>();
    private final Map<Sequence, ArrayDeque<Long>> occurrences = new HashMap<>();

    private long lastTick;
    private boolean hasLastTick;
    private long nextIndex;
    private int retainedTicks;
    private int detectedPeriod;
    private long detectedFirstRepeatTick;

    public void add(long tick, long stateHash) {
        if (hasLastTick && tick <= lastTick) {
            throw new IllegalArgumentException("ticks must be strictly increasing");
        }
        lastTick = tick;
        hasLastTick = true;

        long index = nextIndex++;
        int slot = (int) (index & (CONFIRMATION_TICKS - 1));
        recentHashes[slot] = stateHash;
        recentTicks[slot] = tick;
        if (retainedTicks < MAX_TICKS) {
            retainedTicks++;
        }

        long oldestRetainedIndex = index - retainedTicks + 1;
        while (!windows.isEmpty() && windows.peekFirst().startIndex < oldestRetainedIndex) {
            Window expired = windows.removeFirst();
            ArrayDeque<Long> starts = occurrences.get(expired.sequence);
            starts.removeFirst();
            if (starts.isEmpty()) {
                occurrences.remove(expired.sequence);
            }
        }

        if (index < CONFIRMATION_TICKS - 1) {
            return;
        }

        long startIndex = index - CONFIRMATION_TICKS + 1;
        Sequence sequence = new Sequence(
            recentHashes[(int) (startIndex & 3)],
            recentHashes[(int) ((startIndex + 1) & 3)],
            recentHashes[(int) ((startIndex + 2) & 3)],
            recentHashes[(int) ((startIndex + 3) & 3)]
        );

        ArrayDeque<Long> starts = occurrences.computeIfAbsent(sequence, ignored -> new ArrayDeque<>());
        if (detectedPeriod == 0 && retainedTicks >= CONFIRMATION_TICKS * 2 && !starts.isEmpty()) {
            long previousStart = starts.peekLast();
            long period = startIndex - previousStart;
            if (period > 0 && period <= MAX_TICKS) {
                detectedPeriod = (int) period;
                detectedFirstRepeatTick = recentTicks[(int) (startIndex & 3)];
            }
        }
        starts.addLast(startIndex);
        windows.addLast(new Window(sequence, startIndex));
    }

    public OptionalInt period() {
        return detectedPeriod == 0 ? OptionalInt.empty() : OptionalInt.of(detectedPeriod);
    }

    public OptionalLong firstRepeatTick() {
        return detectedPeriod == 0 ? OptionalLong.empty() : OptionalLong.of(detectedFirstRepeatTick);
    }

    private record Sequence(long first, long second, long third, long fourth) {}
    private record Window(Sequence sequence, long startIndex) {}
}
