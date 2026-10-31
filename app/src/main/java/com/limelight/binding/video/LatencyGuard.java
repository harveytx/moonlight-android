package com.limelight.binding.video;

/**
 * Prevents cumulative latency ("latency creep") when the decoder can't keep up.
 *
 * Moonlight has no video jitter buffer: frames are decoded as soon as they are
 * reassembled. If the hardware decoder is slower than the stream, nothing drops frames
 * on the way in: with direct-submit decoders the receive thread blocks in
 * dequeueInputBuffer() and the backlog silently piles up in the UDP socket buffer
 * (sized for ~2048 packets, i.e. dozens of frames), so latency keeps growing.
 *
 * The guard watches two signals for every frame:
 *  - backlog: queueing delay relative to the host PTS (see {@link JitterEstimator}) plus
 *    the time the frame waited between reassembly and being queued into the decoder.
 *  - decoder pressure: how long the submit path was blocked waiting for a decoder input
 *    buffer, and how many frames are inside the decoder.
 *
 * Only when the backlog stays above a threshold AND the decoder is the bottleneck does
 * it ask for a resync (drop queued frames, wait for an IDR frame). Network-only delay
 * is reported but never acted upon, because an IDR frame would make congestion worse.
 * Resyncs are rate limited; if they keep happening the decoder simply can't sustain
 * the stream settings, so the guard disables itself and raises a warning instead of
 * producing an IDR storm.
 */
public class LatencyGuard {
    public static final int MODE_OFF = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_AGGRESSIVE = 2;

    private static final long COOLDOWN_NS = 3_000_000_000L;
    private static final long GIVE_UP_WINDOW_NS = 60_000_000_000L;
    private static final int GIVE_UP_RESYNCS = 3;
    private static final double EWMA_ALPHA = 0.1;
    private static final int DECODER_IN_FLIGHT_LIMIT = 8;

    private final int mode;
    private final double frameIntervalMs;
    private final double thresholdMs;
    private final int persistenceFrames;

    private int overThresholdFrames;
    private int congestionFrames;
    private double inputWaitEwmaMs;
    private long lastResyncNs;
    private final long[] recentResyncs = new long[GIVE_UP_RESYNCS];
    private int recentResyncIndex;
    private int resyncCount;
    private boolean disabled;
    private boolean decoderSaturated;
    private boolean networkCongested;

    public LatencyGuard(int mode, int fps) {
        this.mode = mode;
        this.frameIntervalMs = 1000.0 / Math.max(fps, 1);

        if (mode == MODE_AGGRESSIVE) {
            thresholdMs = Math.max(2.5 * frameIntervalMs, 30.0);
            persistenceFrames = Math.max((int) Math.ceil(0.4 * fps), 4);
        }
        else {
            thresholdMs = Math.max(5.0 * frameIntervalMs, 60.0);
            persistenceFrames = Math.max(fps, 10);
        }
    }

    /**
     * @param nowNs current time
     * @param backlogMs queueing delay before the decoder (host-relative delay + reassembly to queue time)
     * @param inputWaitMs time this frame's submission was blocked waiting for a decoder input buffer
     * @param decoderInFlight frames queued into the decoder that have not come out yet
     * @return true if the caller should resync (drop this frame and request an IDR frame)
     */
    public boolean onFrame(long nowNs, double backlogMs, double inputWaitMs, int decoderInFlight) {
        inputWaitEwmaMs += EWMA_ALPHA * (inputWaitMs - inputWaitEwmaMs);

        if (mode == MODE_OFF) {
            return false;
        }

        boolean overThreshold = backlogMs > thresholdMs;
        boolean decoderBound = inputWaitEwmaMs > 0.25 * frameIntervalMs ||
                decoderInFlight >= DECODER_IN_FLIGHT_LIMIT;

        if (overThreshold && !decoderBound) {
            congestionFrames++;
            networkCongested = congestionFrames >= persistenceFrames;
        }
        else {
            congestionFrames = 0;
            networkCongested = false;
        }

        if (overThreshold && decoderBound) {
            overThresholdFrames++;
        }
        else {
            overThresholdFrames = 0;
        }

        if (disabled || overThresholdFrames < persistenceFrames) {
            return false;
        }
        if (lastResyncNs != 0 && nowNs - lastResyncNs < COOLDOWN_NS) {
            return false;
        }

        // If the last GIVE_UP_RESYNCS resyncs all happened within the give-up window,
        // the decoder cannot sustain this stream. Stop resyncing and warn instead.
        long oldest = recentResyncs[recentResyncIndex];
        if (oldest != 0 && nowNs - oldest < GIVE_UP_WINDOW_NS) {
            disabled = true;
            decoderSaturated = true;
            return false;
        }

        recentResyncs[recentResyncIndex] = nowNs;
        recentResyncIndex = (recentResyncIndex + 1) % GIVE_UP_RESYNCS;
        lastResyncNs = nowNs;
        overThresholdFrames = 0;
        resyncCount++;
        return true;
    }

    public int getMode() {
        return mode;
    }

    public int getResyncCount() {
        return resyncCount;
    }

    public boolean isDecoderSaturated() {
        return decoderSaturated;
    }

    public boolean isNetworkCongested() {
        return networkCongested;
    }

    public double getThresholdMs() {
        return thresholdMs;
    }
}
