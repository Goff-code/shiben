package cn.focusledger.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class PriorityProgressTest {
    @Test public void ruleBExactExamples() {
        assertArrayEquals(new double[]{1}, PriorityProgress.weights(1), 0);
        assertArrayEquals(new double[]{.5, .5}, PriorityProgress.weights(2), 0);
        assertArrayEquals(new double[]{.5, .25, .25}, PriorityProgress.weights(3), 0);
        assertArrayEquals(new double[]{.5, .25, .125, .125}, PriorityProgress.weights(4), 0);
        assertEquals(65, PriorityProgress.total(Arrays.asList(100, 40, 20)), 0);
    }
    @Test public void weightsStayNormalizedAndNonIncreasing() {
        for (int n = 1; n <= 1000; n++) {
            double sum = 0, last = 1;
            for (double w : PriorityProgress.weights(n)) { assertTrue(w > 0 && w <= last); sum += w; last = w; }
            assertEquals(1, sum, 1e-14);
        }
    }
    @Test public void noProgressIsFabricatedFromUnknownData() {
        assertTrue(Double.isNaN(PriorityProgress.total(Arrays.asList(100, -1, 0))));
        assertTrue(Double.isNaN(PriorityProgress.total(Arrays.asList(100, null, 0))));
        assertEquals(0, PriorityProgress.total(Collections.emptyList()), 0);
        assertEquals(100, PriorityProgress.total(Arrays.asList(100,100,100,100)), 0);
    }
    @Test public void invalidProgressAndRankRejected() {
        for (int p : new int[]{-1, 101, Integer.MAX_VALUE}) {
            try { PriorityProgress.requireProgress(p); fail("accepted invalid progress"); }
            catch (IllegalArgumentException expected) { }
        }
        try { PriorityProgress.weight(0, 3); fail(); } catch (IllegalArgumentException expected) { }
        try { PriorityProgress.weight(4, 3); fail(); } catch (IllegalArgumentException expected) { }
    }
}
