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
 * Thread-safety: all public methods are synchronized. They are called a handful of times
 * per frame from the input, renderer and frame-rendered callback threads, so contention
 * is negligible compared to a frame interval.
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
    public static final int STAGE_COUNT = 10;

    public static final String[] STAGE_KEYS = {
            "receive", "submit", "input_wait", "decode", "pacing",
            "display", "client", "client_display", "queue_delay", "host"
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
    }

    /** Optional per-frame log, kept in memory and written out when the session ends. */
    public static class FrameLog {
        public static final String HEADER = "frame,receive_ms,submit_ms,input_wait_ms,decode_ms,pacing_ms," +
                "rendered,client_ms,queue_delay_ms,host_ms,bytes";
        public static final int COLUMNS = 11;

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
                    if (c == 0 || c == 6 || c == 10) {
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

    private final LatencyHistogram[] windowStages = new LatencyHistogram[STAGE_COUNT];
    private final LatencyHistogram[] globalStages = new LatencyHistogram[STAGE_COUNT];
    private final Counters windowCounters = new Counters();
    private final Counters globalCounters = new Counters();
    private long windowStartNs;
    private long sessionStartNs;
    private long globalDurationNs;

    private final JitterEstimator jitterEstimator = new JitterEstimator();

    // Ring buffer of in-flight frame records, indexed by codec PTS
    private final long[] recPtsUs = new long[RING_SIZE];
    private final long[] recReceiveNs = new long[RING_SIZE];
    private final long[] recEnqueueNs = new long[RING_SIZE];
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

    public PipelineStats() {
        for (int i = 0; i < STAGE_COUNT; i++) {
            windowStages[i] = new LatencyHistogram();
            globalStages[i] = new LatencyHistogram();
        }
    }

    public synchronized void enableFrameLog(int capacityFrames) {
        if (frameLog == null && capacityFrames > 0) {
            frameLog = new FrameLog(capacityFrames);
        }
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
                                           long inputWaitNs, long queuedNs, long hostPtsUs,
                                           int hostLatencyTenthsMs, int frameBytes) {
        startClocksIfNeeded(queuedNs);

        long queueDelayNs = jitterEstimator.onFrame(receiveNs, hostPtsUs);

        windowCounters.framesReceived++;
        windowCounters.framesQueued++;
        windowCounters.bytesReceived += frameBytes;
        inFlight++;

        windowStages[STAGE_RECEIVE].add(enqueueNs - receiveNs);
        windowStages[STAGE_SUBMIT].add(queuedNs - enqueueNs);
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
                    recBytes[idx]);
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
        for (int i = 0; i < STAGE_COUNT; i++) {
            windowStages[i].reset();
            globalStages[i].reset();
        }
        windowCounters.clear();
        globalCounters.clear();
        globalDurationNs = 0;
        windowStartNs = nowNs;
        sessionStartNs = nowNs;
    }

    public synchronized long getTotalFramesDecoded() {
        return globalCounters.framesDecoded + windowCounters.framesDecoded;
    }

    public synchronized long getDecoderInFlight() {
        return inFlight;
    }

    public synchronized double getJitterMs() {
        return jitterEstimator.getJitterMs();
    }

    /**
     * Closes the current measurement window, folds it into the session totals and returns
     * a summary of the window that was just closed.
     */
    public synchronized Snapshot rollWindow(long nowNs) {
        startClocksIfNeeded(nowNs);

        Snapshot snap = new Snapshot();
        for (int i = 0; i < STAGE_COUNT; i++) {
            snap.stages[i] = StageSummary.of(windowStages[i]);
            globalStages[i].merge(windowStages[i]);
            windowStages[i].reset();
        }
        snap.counters.copyFrom(windowCounters);
        snap.durationSec = (nowNs - windowStartNs) / 1_000_000_000.0;
        snap.jitterMs = jitterEstimator.getJitterMs();

        globalCounters.add(windowCounters);
        globalDurationNs += nowNs - windowStartNs;
        windowCounters.clear();
        windowStartNs = nowNs;

        return snap;
    }

    /** Session totals, including the currently open window. */
    public synchronized Snapshot getSessionSnapshot(long nowNs) {
        Snapshot snap = new Snapshot();
        LatencyHistogram tmp = new LatencyHistogram();
        for (int i = 0; i < STAGE_COUNT; i++) {
            tmp.copyFrom(globalStages[i]);
            tmp.merge(windowStages[i]);
            snap.stages[i] = StageSummary.of(tmp);
        }
        snap.counters.copyFrom(globalCounters);
        snap.counters.add(windowCounters);
        long openWindowNs = windowStartNs != 0 ? nowNs - windowStartNs : 0;
        snap.durationSec = (globalDurationNs + openWindowNs) / 1_000_000_000.0;
        snap.jitterMs = jitterEstimator.getJitterMs();
        return snap;
    }

    private static float ms(long ns) {
        return ns / 1_000_000.0f;
    }
}
