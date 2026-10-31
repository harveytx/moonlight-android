package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Fixed-bucket latency histogram used by the ULL pipeline statistics.
 *
 * Resolution is 20 us up to 100 ms and 1 ms up to 2 s; anything above lands in an
 * overflow bucket. Insertion is O(1) and never allocates, so it is safe to call from
 * the video receive/decode threads for every frame. Percentiles are therefore
 * approximate (bucket-quantized), which is well below what we report (0.1 ms).
 *
 * Not thread-safe: callers must provide their own synchronization.
 */
public class LatencyHistogram {
    private static final long FINE_BUCKET_NS = 20_000;          // 20 us
    private static final int FINE_BUCKETS = 5000;                // 0 .. 100 ms
    private static final long COARSE_BUCKET_NS = 1_000_000;     // 1 ms
    private static final int COARSE_BUCKETS = 1900;              // 100 ms .. 2 s
    private static final long FINE_LIMIT_NS = FINE_BUCKET_NS * FINE_BUCKETS;
    private static final int TOTAL_BUCKETS = FINE_BUCKETS + COARSE_BUCKETS + 1;

    private final int[] buckets = new int[TOTAL_BUCKETS];
    private long count;
    private long sumNs;
    private long minNs = Long.MAX_VALUE;
    private long maxNs = Long.MIN_VALUE;

    private static int bucketFor(long ns) {
        if (ns < FINE_LIMIT_NS) {
            return (int) (ns / FINE_BUCKET_NS);
        }
        long coarse = (ns - FINE_LIMIT_NS) / COARSE_BUCKET_NS;
        if (coarse >= COARSE_BUCKETS) {
            return TOTAL_BUCKETS - 1;
        }
        return FINE_BUCKETS + (int) coarse;
    }

    // Returns the upper edge of a bucket, in nanoseconds
    private static long bucketUpperNs(int bucket) {
        if (bucket < FINE_BUCKETS) {
            return (bucket + 1) * FINE_BUCKET_NS;
        }
        return FINE_LIMIT_NS + (bucket - FINE_BUCKETS + 1) * COARSE_BUCKET_NS;
    }

    public void add(long ns) {
        if (ns < 0) {
            // Clock domain mismatch or a reordered sample. Never record negative latency.
            return;
        }
        buckets[bucketFor(ns)]++;
        count++;
        sumNs += ns;
        if (ns < minNs) {
            minNs = ns;
        }
        if (ns > maxNs) {
            maxNs = ns;
        }
    }

    public void merge(LatencyHistogram other) {
        if (other.count == 0) {
            return;
        }
        for (int i = 0; i < TOTAL_BUCKETS; i++) {
            buckets[i] += other.buckets[i];
        }
        count += other.count;
        sumNs += other.sumNs;
        minNs = Math.min(minNs, other.minNs);
        maxNs = Math.max(maxNs, other.maxNs);
    }

    public void copyFrom(LatencyHistogram other) {
        System.arraycopy(other.buckets, 0, buckets, 0, TOTAL_BUCKETS);
        count = other.count;
        sumNs = other.sumNs;
        minNs = other.minNs;
        maxNs = other.maxNs;
    }

    public void reset() {
        Arrays.fill(buckets, 0);
        count = 0;
        sumNs = 0;
        minNs = Long.MAX_VALUE;
        maxNs = Long.MIN_VALUE;
    }

    public long getCount() {
        return count;
    }

    public double getMeanMs() {
        return count == 0 ? 0 : (sumNs / (double) count) / 1_000_000.0;
    }

    public double getMinMs() {
        return count == 0 ? 0 : minNs / 1_000_000.0;
    }

    public double getMaxMs() {
        return count == 0 ? 0 : maxNs / 1_000_000.0;
    }

    /**
     * @param percentile value in [0, 100]
     * @return the latency in ms below which the given percentage of samples fall
     */
    public double getPercentileMs(double percentile) {
        if (count == 0) {
            return 0;
        }
        long target = (long) Math.ceil((percentile / 100.0) * count);
        if (target < 1) {
            target = 1;
        }
        long seen = 0;
        for (int i = 0; i < TOTAL_BUCKETS; i++) {
            seen += buckets[i];
            if (seen >= target) {
                if (i == TOTAL_BUCKETS - 1) {
                    // The overflow bucket has no upper edge
                    return maxNs / 1_000_000.0;
                }
                // Never report beyond the real observed extremes
                long edge = Math.min(bucketUpperNs(i), maxNs);
                return Math.max(edge, minNs) / 1_000_000.0;
            }
        }
        return maxNs / 1_000_000.0;
    }
}
