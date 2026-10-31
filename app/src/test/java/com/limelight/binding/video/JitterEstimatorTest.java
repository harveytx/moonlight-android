package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JitterEstimatorTest {
    private static final long MS = 1_000_000L;
    private static final long FRAME_US = 16_667;

    @Test
    public void constantTransitHasNoJitterOrQueueing() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 120; i++) {
            long pts = i * FRAME_US;
            long arrival = base + pts * 1000 + 3 * MS; // constant 3 ms one-way delay
            long q = j.onFrame(arrival, pts);
            assertEquals(0, q);
        }
        assertEquals(0, j.getJitterMs(), 1e-9);
    }

    @Test
    public void queueingDelayIsMeasuredAgainstTheMinimum() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 60; i++) {
            long pts = i * FRAME_US;
            j.onFrame(base + pts * 1000, pts);
        }
        // A frame delayed by 12 ms
        long pts = 61 * FRAME_US;
        long q = j.onFrame(base + pts * 1000 + 12 * MS, pts);
        assertEquals(12 * MS, q);
        assertTrue(j.getJitterMs() > 0.5);
    }

    @Test
    public void unknownPtsIsUnavailable() {
        JitterEstimator j = new JitterEstimator();
        assertEquals(-1, j.onFrame(1000, 0));
    }

    @Test
    public void ptsDiscontinuityResets() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 30; i++) {
            long pts = i * FRAME_US;
            j.onFrame(base + pts * 1000, pts);
        }
        // Host restarted its clock: transit jumps by ~an hour, must not report an hour of queueing
        long pts = 1 * FRAME_US;
        long q = j.onFrame(base + 3_600_000 * MS, pts);
        assertEquals(0, q);
    }
}
