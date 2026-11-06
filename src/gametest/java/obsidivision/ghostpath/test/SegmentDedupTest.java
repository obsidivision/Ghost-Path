package obsidivision.ghostpath.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import obsidivision.ghostpath.preview.SegmentDedup;

public class SegmentDedupTest {
    @GameTest
    public void identicalSegmentsAreCountedOnce(GameTestHelper helper) {
        SegmentDedup dedup = new SegmentDedup();
        int first = 0;
        int duplicate = 0;
        for (int path = 0; path < 100; path++) {
            for (int segment = 0; segment < 60; segment++) {
                if (dedup.firstTime(0xFF123456, segment, segment, 1, 2, segment + 1, 1, 2)) {
                    first++;
                } else {
                    duplicate++;
                }
            }
        }
        helper.assertTrue(first == 60 && duplicate == 5940, "identical paths: " + first + " first, " + duplicate + " duplicates");
        helper.succeed();
    }

    @GameTest
    public void anotherTickIsDifferent(GameTestHelper helper) {
        SegmentDedup dedup = new SegmentDedup();
        helper.assertTrue(dedup.firstTime(0xFF123456, 1, 0, 0, 0, 1, 1, 1), "first segment");
        helper.assertTrue(dedup.firstTime(0xFF123456, 2, 0, 0, 0, 1, 1, 1), "same positions on another tick");
        helper.succeed();
    }

    @GameTest
    public void differentColourIsDifferent(GameTestHelper helper) {
        SegmentDedup dedup = new SegmentDedup();
        helper.assertTrue(dedup.firstTime(0xFF123456, 1, 0, 0, 0, 1, 1, 1), "first colour");
        helper.assertTrue(dedup.firstTime(0xF1234567, 1, 0, 0, 0, 1, 1, 1), "different colour");
        helper.succeed();
    }

    @GameTest
    public void negativeZeroEqualsZero(GameTestHelper helper) {
        SegmentDedup dedup = new SegmentDedup();
        helper.assertTrue(dedup.firstTime(0xFF123456, 1, -0.0, 0, 0, 1, 1, 1), "negative zero first");
        helper.assertTrue(!dedup.firstTime(0xFF123456, 1, 0.0, -0.0, 0, 1, 1, 1), "negative zero equals zero");
        helper.succeed();
    }

    @GameTest
    public void resetAllowsSegmentsAgain(GameTestHelper helper) {
        SegmentDedup dedup = new SegmentDedup();
        helper.assertTrue(dedup.firstTime(0xFF123456, 1, 0, 0, 0, 1, 1, 1), "first segment");
        helper.assertTrue(!dedup.firstTime(0xFF123456, 1, 0, 0, 0, 1, 1, 1), "duplicate before reset");
        dedup.reset();
        helper.assertTrue(dedup.firstTime(0xFF123456, 1, 0, 0, 0, 1, 1, 1), "segment after reset");
        helper.succeed();
    }
}
