package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LatencyHistogramTest {
    private static final long MS = 1_000_000L;

    @Test
    public void emptyHistogramReportsZero() {
        LatencyHistogram h = new LatencyHistogram();
        assertEquals(0, h.getCount());
        assertEquals(0, h.getMeanMs(), 0);
        assertEquals(0, h.getPercentileMs(95), 0);
    }

    @Test
    public void meanMinMaxAreExact() {
        LatencyHistogram h = new LatencyHistogram();
        h.add(2 * MS);
        h.add(4 * MS);
        h.add(9 * MS);
        assertEquals(3, h.getCount());
        assertEquals(5.0, h.getMeanMs(), 1e-9);
        assertEquals(2.0, h.getMinMs(), 1e-9);
        assertEquals(9.0, h.getMaxMs(), 1e-9);
    }

    @Test
    public void percentilesHaveFineResolution() {
        LatencyHistogram h = new LatencyHistogram();
        // 1.00, 1.01, ..., 1.99 ms
        for (int i = 0; i < 100; i++) {
            h.add(MS + i * 10_000L);
        }
        // 20 us buckets: the error must stay below one bucket
        assertEquals(1.49, h.getPercentileMs(50), 0.021);
        assertEquals(1.94, h.getPercentileMs(95), 0.021);
        assertEquals(1.99, h.getPercentileMs(100), 0.021);
    }

    @Test
    public void coarseRangeAndOverflow() {
        LatencyHistogram h = new LatencyHistogram();
        h.add(150 * MS);
        h.add(5000 * MS); // beyond 2 s goes to the overflow bucket
        assertEquals(151.0, h.getPercentileMs(50), 1.0);
        // Percentiles never exceed the observed maximum
        assertEquals(5000.0, h.getPercentileMs(100), 1e-9);
    }

    @Test
    public void negativeSamplesAreIgnored() {
        LatencyHistogram h = new LatencyHistogram();
        h.add(-5);
        assertEquals(0, h.getCount());
    }

    @Test
    public void mergeAndReset() {
        LatencyHistogram a = new LatencyHistogram();
        LatencyHistogram b = new LatencyHistogram();
        a.add(1 * MS);
        b.add(3 * MS);
        a.merge(b);
        assertEquals(2, a.getCount());
        assertEquals(2.0, a.getMeanMs(), 1e-9);
        assertEquals(3.0, a.getMaxMs(), 1e-9);

        LatencyHistogram c = new LatencyHistogram();
        c.copyFrom(a);
        assertEquals(2, c.getCount());

        a.reset();
        assertEquals(0, a.getCount());
        assertEquals(2, c.getCount());
    }
}
