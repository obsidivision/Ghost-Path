package obsidivision.ghostpath.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import obsidivision.ghostpath.record.CycleDetector;

public class CycleDetectorTest {
    @GameTest
    public void noRepeatStaysEmpty(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        for (int i = 0; i < 100; i++) {
            detector.add(i, i * 17L + 3);
        }
        helper.assertTrue(detector.period().isEmpty() && detector.firstRepeatTick().isEmpty(), "unique hashes must not form a cycle");
        helper.succeed();
    }

    @GameTest
    public void confirmsCycleOfFiveAfterLeadIn(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        long[] hashes = {90, 91, 92, 10, 11, 12, 13, 14, 10, 11, 12, 13, 14, 10, 11, 12, 13, 14};
        for (int i = 0; i < hashes.length; i++) {
            detector.add(100 + i, hashes[i]);
        }
        helper.assertTrue(detector.period().orElse(-1) == 5, "cycle period: " + detector.period());
        helper.assertTrue(detector.firstRepeatTick().orElse(-1) == 108, "first repeated cycle tick: " + detector.firstRepeatTick());
        helper.succeed();
    }

    @GameTest
    public void loneCollisionDoesNotCount(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        long[] hashes = {1, 2, 3, 1, 4, 5, 6};
        for (int i = 0; i < hashes.length; i++) {
            detector.add(i, hashes[i]);
        }
        helper.assertTrue(detector.period().isEmpty() && detector.firstRepeatTick().isEmpty(), "one matching hash is not a cycle");
        helper.succeed();
    }

    @GameTest
    public void oldWindowsAreForgottenAtTheLimit(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        long[] oldWindow = {7, 8, 9, 10};
        for (int i = 0; i < oldWindow.length; i++) {
            detector.add(i, oldWindow[i]);
        }
        for (int i = oldWindow.length; i < 20_000; i++) {
            detector.add(i, 100_000L + i);
        }
        for (int i = 0; i < oldWindow.length; i++) {
            detector.add(20_000L + i, oldWindow[i]);
        }
        helper.assertTrue(detector.period().isEmpty() && detector.firstRepeatTick().isEmpty(), "an expired four-hash window must not form a cycle");
        helper.succeed();
    }

    @GameTest
    public void detectsPeriodOne(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        for (int i = 0; i < 8; i++) {
            detector.add(50 + i, 0x81234567L);
        }
        helper.assertTrue(detector.period().orElse(-1) == 1, "period one");
        helper.assertTrue(detector.firstRepeatTick().orElse(-1) == 51, "period-one first repeat tick");
        helper.succeed();
    }

    @GameTest
    public void rejectsNonIncreasingTicks(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        detector.add(10, 1);
        boolean rejected = false;
        try {
            detector.add(10, 2);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "duplicate ticks must be rejected");
        helper.succeed();
    }
    @GameTest
    public void rejectsSkippedTicks(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        detector.add(10, 1);
        boolean rejected = false;
        try { detector.add(20, 1); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "skipped ticks must not produce sample-based periods");
        helper.succeed();
    }

    @GameTest
    public void matchingPrefixDoesNotConfirmAndDivergenceRevokes(GameTestHelper helper) {
        CycleDetector detector = new CycleDetector();
        long[] prefix = {1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 99, 100};
        for (int i = 0; i < prefix.length; i++) detector.add(i, prefix[i]);
        helper.assertTrue(detector.period().isEmpty(), "a matching four-sample prefix is not a full cycle");
        CycleDetector stable = new CycleDetector();
        for (int i = 0; i < 15; i++) stable.add(i, i % 5);
        helper.assertTrue(stable.period().orElse(-1) == 5, "three complete periods confirm");
        stable.add(15, 99);
        helper.assertTrue(stable.period().isEmpty(), "a mismatch revokes repetition");
        helper.succeed();
    }
}
