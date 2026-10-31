package com.limelight.binding.video;

/**
 * Estimates network jitter and queueing delay for the video stream without any protocol
 * changes, using the host presentation timestamp that Sunshine already sends in every
 * RTP packet.
 *
 * Sunshine sets the RTP timestamp to the capture time of the frame and reports the host
 * processing latency (capture -> packetization) in the frame header. Duplicate frames, which
 * Sunshine sends when the captured content didn't change, have no capture time: their
 * timestamp comes from the rate control schedule and their host latency is 0. Mixing both
 * time bases produced bogus "queueing" of up to ~30 ms on a mostly static desktop, so
 * frames without host latency are ignored.
 *
 * transit = localArrival - (hostPts + hostProcessingLatency), i.e. from the moment the host
 * started sending the frame to the arrival of its first packet. The absolute value is
 * meaningless (different clocks), but its variation is not:
 *  - Jitter is the RFC 3550 interarrival jitter estimator: J += (|D| - J) / 16.
 *  - Queue delay is transit minus the minimum transit seen over a sliding window
 *    (~10 s). It grows when packets wait anywhere between the encoder output and our
 *    recvfrom() call: network queues, Wi-Fi retries, or our own socket buffer when the
 *    decoder can't keep up. The sliding window tracks slow clock drift between hosts.
 *
 * Subtracting the host processing latency removes encoder time variation, so what is left
 * is the network path plus our own socket queue.
 *
 * Not thread-safe.
 */
public class JitterEstimator {
    private static final long BUCKET_DURATION_NS = 1_000_000_000L;
    private static final int WINDOW_BUCKETS = 10;
    // A transit jump larger than this means the host PTS base changed (stream restart)
    private static final long RESET_THRESHOLD_NS = 5_000_000_000L;

    private final long[] bucketMinTransit = new long[WINDOW_BUCKETS];
    private int bucketIndex;
    private long bucketStartNs;

    private boolean hasLast;
    private long lastTransitNs;
    private double jitterNs;

    public JitterEstimator() {
        reset();
    }

    public void reset() {
        for (int i = 0; i < WINDOW_BUCKETS; i++) {
            bucketMinTransit[i] = Long.MAX_VALUE;
        }
        bucketIndex = 0;
        bucketStartNs = 0;
        hasLast = false;
        lastTransitNs = 0;
        jitterNs = 0;
    }

    /**
     * @param arrivalNs local arrival time of the first packet of the frame (System.nanoTime() base)
     * @param hostPtsUs host presentation timestamp of the frame, 0 if unknown
     * @param hostLatencyTenthsMs host processing latency in 0.1 ms units, 0 for duplicate frames
     * @return queue delay in ns relative to the windowed minimum transit, or -1 if unavailable
     */
    public long onFrame(long arrivalNs, long hostPtsUs, int hostLatencyTenthsMs) {
        if (hostPtsUs <= 0 || hostLatencyTenthsMs <= 0) {
            // Unknown PTS, or a duplicate frame whose timestamp isn't a capture time
            return -1;
        }

        long transitNs = arrivalNs - (hostPtsUs * 1000 + hostLatencyTenthsMs * 100_000L);

        if (hasLast && Math.abs(transitNs - lastTransitNs) > RESET_THRESHOLD_NS) {
            reset();
        }

        if (hasLast) {
            long d = Math.abs(transitNs - lastTransitNs);
            jitterNs += (d - jitterNs) / 16.0;
        }
        lastTransitNs = transitNs;
        hasLast = true;

        // Advance the sliding-minimum window
        if (bucketStartNs == 0) {
            bucketStartNs = arrivalNs;
        }
        while (arrivalNs - bucketStartNs >= BUCKET_DURATION_NS) {
            bucketIndex = (bucketIndex + 1) % WINDOW_BUCKETS;
            bucketMinTransit[bucketIndex] = Long.MAX_VALUE;
            bucketStartNs += BUCKET_DURATION_NS;

            // Don't spin through a long gap one second at a time
            if (arrivalNs - bucketStartNs >= BUCKET_DURATION_NS * WINDOW_BUCKETS) {
                for (int i = 0; i < WINDOW_BUCKETS; i++) {
                    bucketMinTransit[i] = Long.MAX_VALUE;
                }
                bucketStartNs = arrivalNs;
            }
        }
        if (transitNs < bucketMinTransit[bucketIndex]) {
            bucketMinTransit[bucketIndex] = transitNs;
        }

        long windowMin = Long.MAX_VALUE;
        for (int i = 0; i < WINDOW_BUCKETS; i++) {
            if (bucketMinTransit[i] < windowMin) {
                windowMin = bucketMinTransit[i];
            }
        }

        return transitNs - windowMin;
    }

    public double getJitterMs() {
        return jitterNs / 1_000_000.0;
    }
}
