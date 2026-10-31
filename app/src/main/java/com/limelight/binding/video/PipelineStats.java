package com.limelight.binding.video;

/**
 * Per-stage latency accounting for the video pipeline (ULL diagnostics).
 *
 * Every frame is tracked through these timestamps, all in the System.nanoTime() clock:
 *
 *   receive   first packet of the frame returned by recvfrom()        (native, mapped)
 *   enqueue   frame fully reassembled by the depacketizer              (native, mapped)
 *   queued    MediaCodec.queueInputBuffer() returned
 *   output    MediaCodec.dequeueOutputBuffer() returned the frame
 *   release   MediaCodec.releaseOutputBuffer() called (render or drop)
 *   display   OnFrameRenderedListener time reported by the system (optional, best effort)
 *
 * All values are measured on the client. The only values that come from elsewhere are
 * the host processing latency (reported by Sunshine) and the queue delay, which is derived
 * from the host PTS and is therefore an estimate (see {@link JitterEstimator}).
 *
 * Thread-safety: the per-frame methods are synchronized and O(1). They are called a handful
 * of times per frame from the input, renderer and frame-rendered callback threads.
 * Closing a measurement window ({@link #rollWindow}) only swaps the active histograms with
 * a spare set while holding the lock; computing percentiles and merging into the session
 * totals happens afterwards without blocking the video threads, so it should be called from
 * a low priority statistics thread.
 *
 * Lock order when both are needed: this, then globalLock.
 */
public class PipelineStats {
    public static final int STAGE_RECEIVE = 0;        // first packet -> frame reassembled
    public static final int STAGE_SUBMIT = 1;         // reassembled -> queued into the decoder
    public static final int STAGE_INPUT_WAIT = 2;     // blocked waiting for a decoder input buffer
    public static final int STAGE_DECODE = 3;         // queued -> decoded frame available
    public static final int STAGE_PACING = 4;         // decoded -> released to the display
    public static final int STAGE_DISPLAY = 5;        // released -> displayed (system-reported)
    public static final int STAGE_CLIENT = 6;         // first packet -> released to the display
    public static final int STAGE_CLIENT_DISPLAY = 7; // first packet -> displayed (system-reported)
    public static final int STAGE_QUEUE_DELAY = 8;    // network/socket queueing vs. window minimum (estimate)
    public static final int STAGE_HOST = 9;           // host processing latency (reported by host)
    public static final int STAGE_JNI = 10;           // reassembled -> Java submitDecodeUnit() entry (part of SUBMIT)
    public static final int STAGE_COUNT = 11;

    // ULL: frame sizes reuse the histogram machinery of the stages, recording one byte as one
    // microsecond: the 20 µs resolution below 100 ms becomes 20 bytes below 100 kB, and the
    // values read back in "ms" are kB (1000 bytes). Not part of the time stages above.
    private static final int HIST_FRAME_SIZE = STAGE_COUNT;
    private static final int HISTOGRAM_COUNT = STAGE_COUNT + 1;

    /** A frame using at least this share of the estimated size ceiling counts as "at the ceiling". */
    public static final double NEAR_CEILING_FRACTION = 0.85;

    public static final String[] STAGE_KEYS = {
            "receive", "submit", "input_wait", "decode", "pacing",
            "display", "client", "client_display", "queue_delay", "host", "jni"
    };

    private static final int RING_SIZE = 128;

    public static class StageSummary {
        public long count;
        public double meanMs, minMs, p50Ms, p95Ms, p99Ms, maxMs;

        static StageSummary of(LatencyHistogram h) {
            StageSummary s = new StageSummary();
            s.count = h.getCount();
            s.meanMs = h.getMeanMs();
            s.minMs = h.getMinMs();
            s.p50Ms = h.getPercentileMs(50);
            s.p95Ms = h.getPercentileMs(95);
            s.p99Ms = h.getPercentileMs(99);
            s.maxMs = h.getMaxMs();
            return s;
        }
    }

    public static class Counters {
        public long framesReceived;       // frames delivered by the depacketizer
        public long framesLostNetwork;    // frames never delivered (gaps in frame numbers)
        public long lossEvents;
        public long framesQueued;         // frames queued into the decoder
        public long framesDecoded;        // frames that came out of the decoder
        public long framesRendered;       // frames released with render=true
        public long framesDroppedRender;  // decoded frames replaced by a newer one before display
        public long framesDisplayed;      // frames reported by OnFrameRenderedListener
        public long resyncs;              // IDR resyncs triggered by the latency guard
        public long bytesReceived;        // picture data bytes delivered to the decoder
        public long framesNearCeiling;    // frames using >= NEAR_CEILING_FRACTION of the size ceiling

        void add(Counters o) {
            framesReceived += o.framesReceived;
            framesLostNetwork += o.framesLostNetwork;
            lossEvents += o.lossEvents;
            framesQueued += o.framesQueued;
            framesDecoded += o.framesDecoded;
            framesRendered += o.framesRendered;
            framesDroppedRender += o.framesDroppedRender;
            framesDisplayed += o.framesDisplayed;
            resyncs += o.resyncs;
            bytesReceived += o.bytesReceived;
            framesNearCeiling += o.framesNearCeiling;
        }

        void copyFrom(Counters o) {
            framesReceived = o.framesReceived;
            framesLostNetwork = o.framesLostNetwork;
            lossEvents = o.lossEvents;
            framesQueued = o.framesQueued;
            framesDecoded = o.framesDecoded;
            framesRendered = o.framesRendered;
            framesDroppedRender = o.framesDroppedRender;
            framesDisplayed = o.framesDisplayed;
            resyncs = o.resyncs;
            bytesReceived = o.bytesReceived;
            framesNearCeiling = o.framesNearCeiling;
        }

        void clear() {
            copyFrom(new Counters());
        }
    }

    public static class Snapshot {
        public final StageSummary[] stages = new StageSummary[STAGE_COUNT];
        public final Counters counters = new Counters();
        public double durationSec;
        public double jitterMs;
        /** Frame sizes: the *Ms fields of this summary hold kB (1000 bytes). */
        public StageSummary frameSizeKb;
        /** Estimated largest frame the host's encoder can send (bytes), 0 if unknown. */
        public int frameCeilingBytes;

        public double fps(long frames) {
            return durationSec > 0 ? frames / durationSec : 0;
        }

        public double networkLossPercent() {
            long total = counters.framesReceived + counters.framesLostNetwork;
            return total > 0 ? 100.0 * counters.framesLostNetwork / total : 0;
        }

        public double renderDropPercent() {
            return counters.framesDecoded > 0 ? 100.0 * counters.framesDroppedRender / counters.framesDecoded : 0;
        }

        public double bitrateMbps() {
            return durationSec > 0 ? (counters.bytesReceived * 8.0) / durationSec / 1_000_000.0 : 0;
        }

        /** Share of frames that used (almost) the whole per-frame budget, or -1 if unknown. */
        public double nearCeilingPercent() {
            if (frameCeilingBytes <= 0 || counters.framesReceived == 0) {
                return -1;
            }
            return 100.0 * counters.framesNearCeiling / counters.framesReceived;
        }
    }

    /** Optional per-frame log, kept in memory and written out when the session ends. */
    public static class FrameLog {
        public static final String HEADER = "frame,receive_ms,submit_ms,input_wait_ms,decode_ms,pacing_ms," +
                "rendered,client_ms,queue_delay_ms,host_ms,bytes,jni_ms,capture";
        public static final int COLUMNS = 13;

        private final float[] data;
        private final int capacity;
        private int size;

        FrameLog(int capacity) {
            this.capacity = capacity;
            this.data = new float[capacity * COLUMNS];
        }

        void append(float... row) {
            if (size >= capacity) {
                return;
            }
            System.arraycopy(row, 0, data, size * COLUMNS, COLUMNS);
            size++;
        }

        public int size() {
            return size;
        }

        public boolean isFull() {
            return size >= capacity;
        }

        public void writeCsv(Appendable out) throws java.io.IOException {
            out.append(HEADER).append('\n');
            StringBuilder sb = new StringBuilder(128);
            for (int i = 0; i < size; i++) {
                sb.setLength(0);
                for (int c = 0; c < COLUMNS; c++) {
                    if (c > 0) {
                        sb.append(',');
                    }
                    float v = data[i * COLUMNS + c];
                    if (c == 0 || c == 6 || c == 10 || c == 12) {
                        sb.append((long) v);
                    }
                    else if (v < 0) {
                        // Not available for this frame
                        sb.append("");
                    }
                    else {
                        sb.append(String.format(java.util.Locale.ROOT, "%.3f", v));
                    }
                }
                out.append(sb).append('\n');
            }
        }
    }

    // Active window (guarded by this) and its spare, swapped by rollWindow()
    private LatencyHistogram[] windowStages = newStageArray();
    private Counters windowCounters = new Counters();
    private LatencyHistogram[] spareStages = newStageArray();
    private Counters spareCounters = new Counters();
    private long windowStartNs;
    private long sessionStartNs;

    // Session totals and the window currently being folded into them (guarded by globalLock)
    private final Object globalLock = new Object();
    private final LatencyHistogram[] globalStages = newStageArray();
    private final Counters globalCounters = new Counters();
    private long globalDurationNs;
    private LatencyHistogram[] closingStages;
    private Counters closingCounters;
    private long closingDurationNs;
    private long closingGeneration;
    // Incremented by resetStatistics() so a window closed before the reset is discarded
    private long generation;

    private final JitterEstimator jitterEstimator = new JitterEstimator();

    // Ring buffer of in-flight frame records, indexed by codec PTS
    private final long[] recPtsUs = new long[RING_SIZE];
    private final long[] recReceiveNs = new long[RING_SIZE];
    private final long[] recEnqueueNs = new long[RING_SIZE];
    private final long[] recEntryNs = new long[RING_SIZE];
    private final long[] recQueuedNs = new long[RING_SIZE];
    private final long[] recOutputNs = new long[RING_SIZE];
    private final long[] recReleaseNs = new long[RING_SIZE];
    private final long[] recInputWaitNs = new long[RING_SIZE];
    private final long[] recQueueDelayNs = new long[RING_SIZE];
    private final int[] recFrameNumber = new int[RING_SIZE];
    private final int[] recHostTenthsMs = new int[RING_SIZE];
    private final int[] recBytes = new int[RING_SIZE];
    private final boolean[] recValid = new boolean[RING_SIZE];
    private int recNext;

    private long inFlight;
    private FrameLog frameLog;
    private int frameCeilingBytes;
    // ULL: set when a video capture is taken; the next logged frame is marked in the CSV
    private boolean captureMark;

    private static LatencyHistogram[] newStageArray() {
        LatencyHistogram[] stages = new LatencyHistogram[HISTOGRAM_COUNT];
        for (int i = 0; i < HISTOGRAM_COUNT; i++) {
            stages[i] = new LatencyHistogram();
        }
        return stages;
    }

    public synchronized void enableFrameLog(int capacityFrames) {
        if (frameLog == null && capacityFrames > 0) {
            frameLog = new FrameLog(capacityFrames);
        }
    }

    /**
     * Sets the estimated largest frame the host can send at the configured bitrate (see
     * {@link BitrateBudget}); frames close to it are counted as limited by the bitrate.
     */
    public synchronized void setFrameCeilingBytes(int bytes) {
        frameCeilingBytes = Math.max(bytes, 0);
    }

    public synchronized int getFrameCeilingBytes() {
        return frameCeilingBytes;
    }

    /** Marks the next frame in the per-frame log (a video capture was taken at this moment). */
    public synchronized void markCapture() {
        captureMark = true;
    }

    public synchronized FrameLog getFrameLog() {
        return frameLog;
    }

    private void startClocksIfNeeded(long nowNs) {
        if (windowStartNs == 0) {
            windowStartNs = nowNs;
        }
        if (sessionStartNs == 0) {
            sessionStartNs = nowNs;
        }
    }

    private int findRecord(long ptsUs) {
        // Newest first: outputs almost always match one of the last few frames queued
        for (int i = 1; i <= RING_SIZE; i++) {
            int idx = (recNext - i + RING_SIZE) % RING_SIZE;
            if (recValid[idx] && recPtsUs[idx] == ptsUs) {
                return idx;
            }
        }
        return -1;
    }

    public synchronized void onFramesLost(int frames) {
        if (frames <= 0) {
            return;
        }
        windowCounters.framesLostNetwork += frames;
        windowCounters.lossEvents++;
    }

    /**
     * Records a frame that has been queued into the decoder.
     *
     * @return estimated network/socket queueing delay in ns, or -1 if not available
     */
    public synchronized long onFrameQueued(long ptsUs, int frameNumber, long receiveNs, long enqueueNs,
                                           long entryNs, long inputWaitNs, long queuedNs, long hostPtsUs,
                                           int hostLatencyTenthsMs, int frameBytes) {
        startClocksIfNeeded(queuedNs);

        long queueDelayNs = jitterEstimator.onFrame(receiveNs, hostPtsUs, hostLatencyTenthsMs);

        windowCounters.framesReceived++;
        windowCounters.framesQueued++;
        windowCounters.bytesReceived += frameBytes;
        if (frameCeilingBytes > 0 && frameBytes >= NEAR_CEILING_FRACTION * frameCeilingBytes) {
            windowCounters.framesNearCeiling++;
        }
        inFlight++;

        windowStages[HIST_FRAME_SIZE].add(frameBytes * 1000L);

        windowStages[STAGE_RECEIVE].add(enqueueNs - receiveNs);
        windowStages[STAGE_SUBMIT].add(queuedNs - enqueueNs);
        windowStages[STAGE_JNI].add(entryNs - enqueueNs);
        windowStages[STAGE_INPUT_WAIT].add(inputWaitNs);
        if (queueDelayNs >= 0) {
            windowStages[STAGE_QUEUE_DELAY].add(queueDelayNs);
        }
        if (hostLatencyTenthsMs > 0) {
            windowStages[STAGE_HOST].add(hostLatencyTenthsMs * 100_000L);
        }

        int idx = recNext;
        recNext = (recNext + 1) % RING_SIZE;
        recValid[idx] = true;
        recPtsUs[idx] = ptsUs;
        recFrameNumber[idx] = frameNumber;
        recReceiveNs[idx] = receiveNs;
        recEnqueueNs[idx] = enqueueNs;
        recEntryNs[idx] = entryNs;
        recQueuedNs[idx] = queuedNs;
        recInputWaitNs[idx] = inputWaitNs;
        recQueueDelayNs[idx] = queueDelayNs;
        recHostTenthsMs[idx] = hostLatencyTenthsMs;
        recBytes[idx] = frameBytes;
        recOutputNs[idx] = 0;
        recReleaseNs[idx] = 0;

        return queueDelayNs;
    }

    public synchronized void onFrameOutput(long ptsUs, long outputNs) {
        windowCounters.framesDecoded++;
        if (inFlight > 0) {
            inFlight--;
        }

        int idx = findRecord(ptsUs);
        if (idx >= 0 && recOutputNs[idx] == 0) {
            recOutputNs[idx] = outputNs;
            windowStages[STAGE_DECODE].add(outputNs - recQueuedNs[idx]);
        }
    }

    public synchronized void onFrameReleased(long ptsUs, long releaseNs, boolean rendered) {
        if (rendered) {
            windowCounters.framesRendered++;
        }
        else {
            windowCounters.framesDroppedRender++;
        }

        int idx = findRecord(ptsUs);
        if (idx < 0 || recReleaseNs[idx] != 0) {
            return;
        }
        recReleaseNs[idx] = releaseNs;

        if (!rendered) {
            return;
        }

        if (recOutputNs[idx] != 0) {
            windowStages[STAGE_PACING].add(releaseNs - recOutputNs[idx]);
        }
        windowStages[STAGE_CLIENT].add(releaseNs - recReceiveNs[idx]);

        if (frameLog != null && !frameLog.isFull()) {
            frameLog.append(
                    recFrameNumber[idx],
                    ms(recEnqueueNs[idx] - recReceiveNs[idx]),
                    ms(recQueuedNs[idx] - recEnqueueNs[idx]),
                    ms(recInputWaitNs[idx]),
                    recOutputNs[idx] != 0 ? ms(recOutputNs[idx] - recQueuedNs[idx]) : -1,
                    recOutputNs[idx] != 0 ? ms(releaseNs - recOutputNs[idx]) : -1,
                    1,
                    ms(releaseNs - recReceiveNs[idx]),
                    recQueueDelayNs[idx] >= 0 ? ms(recQueueDelayNs[idx]) : -1,
                    recHostTenthsMs[idx] / 10.0f,
                    recBytes[idx],
                    ms(recEntryNs[idx] - recEnqueueNs[idx]),
                    captureMark ? 1 : 0);
            captureMark = false;
        }
    }

    public synchronized void onFrameDisplayed(long ptsUs, long displayNs) {
        windowCounters.framesDisplayed++;

        int idx = findRecord(ptsUs);
        if (idx < 0) {
            return;
        }
        if (recReleaseNs[idx] != 0) {
            windowStages[STAGE_DISPLAY].add(displayNs - recReleaseNs[idx]);
        }
        windowStages[STAGE_CLIENT_DISPLAY].add(displayNs - recReceiveNs[idx]);
        // A frame is only displayed once
        recValid[idx] = false;
    }

    public synchronized void onResync() {
        windowCounters.resyncs++;
    }

    /** Called when the decoder was flushed/reset and queued frames will never come out. */
    public synchronized void onDecoderReset() {
        inFlight = 0;
        for (int i = 0; i < RING_SIZE; i++) {
            recValid[i] = false;
        }
    }

    /**
     * Discards all accumulated statistics (window and session) but keeps in-flight frame
     * records, so frames already inside the decoder are still matched. Used by the decoder
     * benchmark to exclude warm-up frames.
     */
    public synchronized void resetStatistics(long nowNs) {
        for (LatencyHistogram h : windowStages) {
            h.reset();
        }
        windowCounters.clear();
        windowStartNs = nowNs;
        sessionStartNs = nowNs;
        synchronized (globalLock) {
            for (LatencyHistogram h : globalStages) {
                h.reset();
            }
            globalCounters.clear();
            globalDurationNs = 0;
            generation++;
        }
    }

    public synchronized long getTotalFramesDecoded() {
        synchronized (globalLock) {
            long total = globalCounters.framesDecoded + windowCounters.framesDecoded;
            if (closingCounters != null && closingGeneration == generation) {
                total += closingCounters.framesDecoded;
            }
            return total;
        }
    }

    public synchronized long getTotalFramesDisplayed() {
        synchronized (globalLock) {
            long total = globalCounters.framesDisplayed + windowCounters.framesDisplayed;
            if (closingCounters != null && closingGeneration == generation) {
                total += closingCounters.framesDisplayed;
            }
            return total;
        }
    }

    public synchronized long getDecoderInFlight() {
        return inFlight;
    }

    public synchronized double getJitterMs() {
        return jitterEstimator.getJitterMs();
    }

    /**
     * Closes the current measurement window, folds it into the session totals and returns
     * a summary of the window that was just closed. The video threads are only blocked for
     * the O(1) swap; call this from a statistics thread, not from the video threads.
     * Must not be called concurrently with itself.
     */
    public Snapshot rollWindow(long nowNs) {
        LatencyHistogram[] closed;
        Counters closedCounters;
        double jitterMs;
        long closedDurationNs;
        long closedGeneration;
        int ceilingBytes;

        synchronized (this) {
            startClocksIfNeeded(nowNs);
            closed = windowStages;
            closedCounters = windowCounters;
            windowStages = spareStages != null ? spareStages : newStageArray();
            windowCounters = spareCounters != null ? spareCounters : new Counters();
            spareStages = null;
            spareCounters = null;
            closedDurationNs = nowNs - windowStartNs;
            windowStartNs = nowNs;
            jitterMs = jitterEstimator.getJitterMs();
            ceilingBytes = frameCeilingBytes;

            synchronized (globalLock) {
                // Visible to getSessionSnapshot() until it has been merged
                closingStages = closed;
                closingCounters = closedCounters;
                closingDurationNs = closedDurationNs;
                closingGeneration = closedGeneration = generation;
            }
        }

        // Percentiles are computed without holding any lock
        Snapshot snap = new Snapshot();
        for (int i = 0; i < STAGE_COUNT; i++) {
            snap.stages[i] = StageSummary.of(closed[i]);
        }
        snap.frameSizeKb = StageSummary.of(closed[HIST_FRAME_SIZE]);
        snap.frameCeilingBytes = ceilingBytes;
        snap.counters.copyFrom(closedCounters);
        snap.durationSec = closedDurationNs / 1_000_000_000.0;
        snap.jitterMs = jitterMs;

        synchronized (globalLock) {
            if (closedGeneration == generation) {
                for (int i = 0; i < HISTOGRAM_COUNT; i++) {
                    globalStages[i].merge(closed[i]);
                }
                globalCounters.add(closedCounters);
                globalDurationNs += closedDurationNs;
            }
            closingStages = null;
            closingCounters = null;
        }

        for (LatencyHistogram h : closed) {
            h.reset();
        }
        closedCounters.clear();
        synchronized (this) {
            spareStages = closed;
            spareCounters = closedCounters;
        }

        return snap;
    }

    /** Session totals, including the currently open window. */
    public synchronized Snapshot getSessionSnapshot(long nowNs) {
        synchronized (globalLock) {
            boolean includeClosing = closingStages != null && closingGeneration == generation;
            Snapshot snap = new Snapshot();
            LatencyHistogram tmp = new LatencyHistogram();
            for (int i = 0; i < HISTOGRAM_COUNT; i++) {
                tmp.copyFrom(globalStages[i]);
                if (includeClosing) {
                    tmp.merge(closingStages[i]);
                }
                tmp.merge(windowStages[i]);
                if (i == HIST_FRAME_SIZE) {
                    snap.frameSizeKb = StageSummary.of(tmp);
                }
                else {
                    snap.stages[i] = StageSummary.of(tmp);
                }
            }
            snap.frameCeilingBytes = frameCeilingBytes;
            snap.counters.copyFrom(globalCounters);
            if (includeClosing) {
                snap.counters.add(closingCounters);
            }
            snap.counters.add(windowCounters);
            long openWindowNs = windowStartNs != 0 ? nowNs - windowStartNs : 0;
            snap.durationSec = (globalDurationNs + (includeClosing ? closingDurationNs : 0) + openWindowNs)
                    / 1_000_000_000.0;
            snap.jitterMs = jitterEstimator.getJitterMs();
            return snap;
        }
    }

    private static float ms(long ns) {
        return ns / 1_000_000.0f;
    }
}
