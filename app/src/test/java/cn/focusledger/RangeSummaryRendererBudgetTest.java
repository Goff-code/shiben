package cn.focusledger;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public final class RangeSummaryRendererBudgetTest {
    @Test public void maximumImageIncludesBackgroundAndAuxiliaryAllocationsBelow48MiB() throws Exception {
        long bytes = RangeSummaryRenderer.estimatedBitmapBytes(RangeSummaryRenderer.WIDTH,
                RangeSummaryRenderer.MAX_HEIGHT, RangeSummaryRenderer.MAX_BACKGROUND_PIXELS);
        assertEquals(45_045_760L, bytes);
        assertTrue(bytes < RangeSummaryRenderer.BITMAP_BUDGET_BYTES);
        assertEquals(48L * 1024 * 1024, RangeSummaryRenderer.BITMAP_BUDGET_BYTES);
        assertEquals(8_640_000L, RangeSummaryRenderer.MAX_OUTPUT_PIXELS);
        RangeSummaryRenderer.validateBitmapBudget(1080, 8000, RangeSummaryRenderer.MAX_BACKGROUND_PIXELS);
    }

    @Test public void measuredHeightAcceptsExactLimitAndNeverSilentlyClamps() throws Exception {
        assertEquals(1100, RangeSummaryRenderer.checkedHeight(100));
        assertEquals(8000, RangeSummaryRenderer.checkedHeight(8000));
        try { RangeSummaryRenderer.checkedHeight(8001); fail("must not crop the final row"); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("缩小日期范围")); assertTrue(expected.getMessage().contains("Markdown"));
        }
    }

    @Test(expected = IOException.class) public void oneExtraBackgroundPixelIsRejected() throws Exception {
        RangeSummaryRenderer.validateBitmapBudget(1080, 1100, RangeSummaryRenderer.MAX_BACKGROUND_PIXELS + 1);
    }

    @Test(expected = IOException.class) public void negativeBackgroundAllocationIsRejected() throws Exception {
        RangeSummaryRenderer.validateBitmapBudget(1080, 1100, -1);
    }

    @Test(expected = IOException.class) public void dimensionsCannotExpandWidthBehindTheBudget() throws Exception {
        RangeSummaryRenderer.validateBitmapBudget(1081, 1100, 0);
    }

    @Test(expected = IOException.class) public void nonpositiveHeightIsRejected() throws Exception {
        RangeSummaryRenderer.validateBitmapBudget(1080, 0, 0);
    }

    @Test(expected = IOException.class) public void allocationMultiplicationOverflowFailsClosed() throws Exception {
        RangeSummaryRenderer.estimatedBitmapBytes(Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Test public void actualApprovedBackgroundPixelsFitWithoutDensityScaling() throws Exception {
        long actualBackground = 480L * 1021;
        assertTrue(actualBackground < RangeSummaryRenderer.MAX_BACKGROUND_PIXELS);
        RangeSummaryRenderer.validateBitmapBudget(1080, 8000, actualBackground);
        assertEquals(34_560_000L + actualBackground * 4L + 8_388_608L,
                RangeSummaryRenderer.estimatedBitmapBytes(1080, 8000, actualBackground));
    }

    @Test public void hiddenTasksUseCompactHeightWithTheSameSafeAllocationPreflight() throws Exception {
        assertEquals(740, RangeSummaryRenderer.HIDDEN_TASK_HEIGHT);
        assertEquals(740, RangeSummaryRenderer.checkedHeight(740, true));
        assertEquals(1100, RangeSummaryRenderer.checkedHeight(740, false));
        long bytes = RangeSummaryRenderer.estimatedBitmapBytes(1080, 740, RangeSummaryRenderer.MAX_BACKGROUND_PIXELS);
        assertEquals(13_682_560L, bytes);
        assertTrue(bytes < RangeSummaryRenderer.BITMAP_BUDGET_BYTES);
    }
}
