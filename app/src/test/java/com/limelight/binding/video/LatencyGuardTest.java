package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LatencyGuardTest {
    private static final long MS = 1_000_000L;
    private static final int FPS = 60;
    private static final long FRAME_NS = 1_000_000_000L / FPS;

    // Feeds frames until the guard asks for a resync; returns the number of frames fed, or -1
    private static int feedUntilResync(LatencyGuard g, long[] now, int maxFrames,
                                       double backlogMs, double inputWaitMs, int inFlight) {
        for (int i = 1; i <= maxFrames; i++) {
            now[0] += FRAME_NS;
            if (g.onFrame(now[0], backlogMs, inputWaitMs, inFlight)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void offModeNeverResyncs() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_OFF, FPS);
        long[] now = {1};
        assertEquals(-1, feedUntilResync(g, now, 600, 500, 16, 10));
    }

    @Test
    public void healthyPipelineNeverResyncs() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_AGGRESSIVE, FPS);
        long[] now = {1};
        assertEquals(-1, feedUntilResync(g, now, 6000, 2, 0.1, 2));
    }

    @Test
    public void networkOnlyDelayIsReportedButNotActedUpon() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_AGGRESSIVE, FPS);
        long[] now = {1};
        // Large backlog, but the decoder is never blocking: congestion, not a decoder problem
        assertEquals(-1, feedUntilResync(g, now, 600, 200, 0, 1));
        assertTrue(g.isNetworkCongested());
    }

    @Test
    public void persistentDecoderBacklogResyncs() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_AGGRESSIVE, FPS);
        long[] now = {1};
        int frames = feedUntilResync(g, now, 600, 120, 12, 3);
        assertTrue("guard must trigger", frames > 0);
        // Aggressive mode needs ~0.4 s of persistence (plus the input wait EWMA warm-up)
        assertTrue(frames >= 24 && frames < 60);
        assertEquals(1, g.getResyncCount());
    }

    @Test
    public void shortSpikeDoesNotResync() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_AGGRESSIVE, FPS);
        long[] now = {1};
        // 10 frames of trouble, then healthy again
        assertEquals(-1, feedUntilResync(g, now, 10, 120, 12, 3));
        assertEquals(-1, feedUntilResync(g, now, 600, 2, 0.1, 2));
    }

    @Test
    public void cooldownAndGiveUp() {
        LatencyGuard g = new LatencyGuard(LatencyGuard.MODE_AGGRESSIVE, FPS);
        long[] now = {1};
        // Keep the decoder saturated for a long time
        int resyncs = 0;
        long lastResync = -1;
        for (int i = 0; i < 60 * 60; i++) {
            now[0] += FRAME_NS;
            if (g.onFrame(now[0], 120, 12, 3)) {
                if (lastResync > 0) {
                    assertTrue("cooldown respected", now[0] - lastResync >= 3_000 * MS);
                }
                lastResync = now[0];
                resyncs++;
            }
        }
        // At most 3 resyncs, then it gives up and flags the decoder as saturated
        assertEquals(3, resyncs);
        assertTrue(g.isDecoderSaturated());
        assertFalse(g.onFrame(now[0] + 10_000 * MS, 120, 12, 3));
    }
}
