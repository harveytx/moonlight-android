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
            long q = j.onFrame(arrival, pts, 50);
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
            j.onFrame(base + pts * 1000, pts, 50);
        }
        // A frame delayed by 12 ms
        long pts = 61 * FRAME_US;
        long q = j.onFrame(base + pts * 1000 + 12 * MS, pts, 50);
        assertEquals(12 * MS, q);
        assertTrue(j.getJitterMs() > 0.5);
    }

    @Test
    public void unknownPtsIsUnavailable() {
        JitterEstimator j = new JitterEstimator();
        assertEquals(-1, j.onFrame(1000, 0, 50));
    }

    @Test
    public void ptsDiscontinuityResets() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 30; i++) {
            long pts = i * FRAME_US;
            j.onFrame(base + pts * 1000, pts, 50);
        }
        // Host restarted its clock: transit jumps by ~an hour, must not report an hour of queueing
        long pts = 1 * FRAME_US;
        long q = j.onFrame(base + 3_600_000 * MS, pts, 50);
        assertEquals(0, q);
    }

    @Test
    public void duplicateFramesAreIgnored() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 60; i++) {
            long pts = i * FRAME_US;
            j.onFrame(base + pts * 1000, pts, 50);
        }
        // Sunshine duplicate frame: no capture time (host latency 0), PTS from rate control
        long pts = 61 * FRAME_US - 30_000;
        assertEquals(-1, j.onFrame(base + 61 * FRAME_US * 1000, pts, 0));
        // ...and it did not disturb the estimate for real frames
        pts = 62 * FRAME_US;
        assertEquals(0, j.onFrame(base + pts * 1000, pts, 50));
    }

    @Test
    public void hostProcessingVariationIsNotNetworkJitter() {
        JitterEstimator j = new JitterEstimator();
        long base = 5_000 * MS;
        for (int i = 1; i <= 120; i++) {
            long pts = i * FRAME_US;
            // Encoding takes 3 or 9 ms alternately; the network delay is constant
            int hostTenthsMs = (i % 2 == 0) ? 30 : 90;
            long arrival = base + pts * 1000 + hostTenthsMs * 100_000L + 2 * MS;
            assertEquals(0, j.onFrame(arrival, pts, hostTenthsMs));
        }
        assertEquals(0, j.getJitterMs(), 1e-9);
    }
}
