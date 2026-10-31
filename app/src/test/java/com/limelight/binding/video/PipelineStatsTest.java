package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.StringWriter;

public class PipelineStatsTest {
    private static final long MS = 1_000_000L;

    @Test
    public void stagesAreComputedPerFrame() throws Exception {
        PipelineStats s = new PipelineStats();
        s.enableFrameLog(10);
        long t = 10_000 * MS;
        // first packet t, reassembled +2 ms, queued +2.5 ms, decoded +10.5 ms, released +10.6, displayed +25
        s.onFrameQueued(1000, 1, t, t + 2 * MS, t + 2_200_000, 100_000, t + 2500_000, 16_667, 40, 30000);
        assertEquals(1, s.getDecoderInFlight());
        s.onFrameOutput(1000, t + 10_500_000);
        assertEquals(0, s.getDecoderInFlight());
        s.onFrameReleased(1000, t + 10_600_000, true);
        s.onFrameDisplayed(1000, t + 25 * MS);

        PipelineStats.Snapshot snap = s.getSessionSnapshot(t + 1000 * MS);
        assertEquals(2.0, snap.stages[PipelineStats.STAGE_RECEIVE].meanMs, 0.03);
        assertEquals(0.5, snap.stages[PipelineStats.STAGE_SUBMIT].meanMs, 0.03);
        assertEquals(0.2, snap.stages[PipelineStats.STAGE_JNI].meanMs, 0.03);
        assertEquals(0.1, snap.stages[PipelineStats.STAGE_INPUT_WAIT].meanMs, 0.03);
        assertEquals(8.0, snap.stages[PipelineStats.STAGE_DECODE].meanMs, 0.03);
        assertEquals(0.1, snap.stages[PipelineStats.STAGE_PACING].meanMs, 0.03);
        assertEquals(14.4, snap.stages[PipelineStats.STAGE_DISPLAY].meanMs, 0.03);
        assertEquals(10.6, snap.stages[PipelineStats.STAGE_CLIENT].meanMs, 0.03);
        assertEquals(25.0, snap.stages[PipelineStats.STAGE_CLIENT_DISPLAY].meanMs, 0.03);
        assertEquals(4.0, snap.stages[PipelineStats.STAGE_HOST].meanMs, 0.03);
        assertEquals(1, snap.counters.framesRendered);
        assertEquals(1, snap.counters.framesDisplayed);

        StringWriter csv = new StringWriter();
        s.getFrameLog().writeCsv(csv);
        String[] lines = csv.toString().split("\n");
        assertEquals(2, lines.length);
        assertEquals(PipelineStats.FrameLog.HEADER, lines[0]);
    }

    @Test
    public void droppedFramesAndLossAreCounted() {
        PipelineStats s = new PipelineStats();
        long t = 10_000 * MS;
        s.onFrameQueued(1000, 1, t, t, t, 0, t, 0, 0, 100);
        s.onFrameQueued(2000, 2, t, t, t, 0, t, 0, 0, 100);
        s.onFrameOutput(1000, t + MS);
        s.onFrameOutput(2000, t + MS);
        s.onFrameReleased(1000, t + MS, false);
        s.onFrameReleased(2000, t + MS, true);
        s.onFramesLost(3);

        PipelineStats.Snapshot w = s.rollWindow(t + 1000 * MS);
        assertEquals(2, w.counters.framesReceived);
        assertEquals(3, w.counters.framesLostNetwork);
        assertEquals(1, w.counters.lossEvents);
        assertEquals(1, w.counters.framesDroppedRender);
        assertEquals(50.0, w.renderDropPercent(), 1e-9);
        assertEquals(60.0, w.networkLossPercent(), 1e-9);

        // The window was folded into the session totals
        PipelineStats.Snapshot session = s.getSessionSnapshot(t + 2000 * MS);
        assertEquals(2, session.counters.framesReceived);
        assertEquals(2.0, session.durationSec, 1e-6);

        s.resetStatistics(t + 2000 * MS);
        assertEquals(0, s.getSessionSnapshot(t + 2000 * MS).counters.framesReceived);
    }

    @Test
    public void decoderResetClearsInFlight() {
        PipelineStats s = new PipelineStats();
        s.onFrameQueued(1000, 1, 1, 1, 1, 0, 1, 0, 0, 100);
        s.onFrameQueued(2000, 2, 1, 1, 1, 0, 1, 0, 0, 100);
        assertEquals(2, s.getDecoderInFlight());
        s.onDecoderReset();
        assertEquals(0, s.getDecoderInFlight());
    }

    @Test
    public void repeatedRollsKeepWindowsSeparate() {
        PipelineStats s = new PipelineStats();
        long t = 10_000 * MS;
        long pts = 1000;
        for (int window = 1; window <= 5; window++) {
            // window N has N frames with N ms decode time
            for (int f = 0; f < window; f++) {
                s.onFrameQueued(pts, (int) pts, t, t, t, 0, t, 0, 0, 100);
                s.onFrameOutput(pts, t + window * MS);
                pts++;
            }
            PipelineStats.Snapshot w = s.rollWindow(t + window * 1000 * MS);
            assertEquals(window, w.counters.framesDecoded);
            assertEquals(window, w.stages[PipelineStats.STAGE_DECODE].meanMs, 0.03);
        }
        PipelineStats.Snapshot session = s.getSessionSnapshot(t + 5000 * MS);
        assertEquals(15, session.counters.framesDecoded);
        assertEquals(15, s.getTotalFramesDecoded());
        // (1*1 + 2*2 + 3*3 + 4*4 + 5*5) / 15
        assertEquals(55.0 / 15.0, session.stages[PipelineStats.STAGE_DECODE].meanMs, 0.03);
    }

    @Test
    public void resetDiscardsEverythingBefore() {
        PipelineStats s = new PipelineStats();
        long t = 10_000 * MS;
        s.onFrameQueued(1000, 1, t, t, t, 0, t, 0, 0, 100);
        s.onFrameOutput(1000, t + 50 * MS);
        s.rollWindow(t + 1000 * MS);
        s.resetStatistics(t + 1000 * MS);
        s.onFrameQueued(2000, 2, t, t, t, 0, t, 0, 0, 100);
        s.onFrameOutput(2000, t + 2 * MS);
        PipelineStats.Snapshot session = s.getSessionSnapshot(t + 2000 * MS);
        assertEquals(1, session.counters.framesDecoded);
        assertEquals(2.0, session.stages[PipelineStats.STAGE_DECODE].meanMs, 0.03);
    }
}
