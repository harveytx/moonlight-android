package com.limelight.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.limelight.binding.video.MediaCodecHelper;

import org.junit.Test;

public class DecoderBenchmarkRecommendationTest {
    private static DecoderBenchmark.ConfigResult config(int mode, double p95, boolean valid) {
        DecoderBenchmark.ConfigResult r = new DecoderBenchmark.ConfigResult();
        r.mode = mode;
        r.decodeP95Ms = p95;
        r.valid = valid;
        return r;
    }

    @Test
    public void keepsOriginalWithoutSignificantGain() {
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 10.0, true));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_STANDARD, 9.7, true));
        DecoderBenchmark.chooseRecommendation(result);
        assertEquals(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, result.recommended.mode);
    }

    @Test
    public void picksClearlyFasterConfiguration() {
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 12.0, true));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_VENDOR, 7.0, true));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_OFF, 6.0, false));
        DecoderBenchmark.chooseRecommendation(result);
        assertEquals(MediaCodecHelper.LOW_LATENCY_MODE_VENDOR, result.recommended.mode);
    }

    @Test
    public void fallsBackWhenOriginalFails() {
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 5.0, false));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_OFF, 9.0, true));
        DecoderBenchmark.chooseRecommendation(result);
        assertEquals(MediaCodecHelper.LOW_LATENCY_MODE_OFF, result.recommended.mode);
    }

    @Test
    public void noRecommendationWhenNothingWorks() {
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 5.0, false));
        DecoderBenchmark.chooseRecommendation(result);
        assertNull(result.recommended);
    }

    private static DecoderBenchmark.ConfigResult config(int mode, double p95, double... rounds) {
        DecoderBenchmark.ConfigResult r = config(mode, p95, true);
        for (double v : rounds) {
            r.roundP95Ms.add(v);
        }
        return r;
    }

    @Test
    public void rejectsGainThatIsNotReproducedInEveryRound() {
        // Like the ZTE HEVC runs: better on average, but one round was slower than original
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 3.4, 4.6, 2.6, 3.0));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_VENDOR, 2.7, 2.6, 2.8, 2.7));
        DecoderBenchmark.chooseRecommendation(result);
        assertEquals(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, result.recommended.mode);
    }

    @Test
    public void acceptsGainReproducedInEveryRound() {
        DecoderBenchmark.Result result = new DecoderBenchmark.Result();
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, 4.0, 4.1, 3.9, 4.0));
        result.configs.add(config(MediaCodecHelper.LOW_LATENCY_MODE_STANDARD, 2.6, 2.6, 2.5, 2.7));
        DecoderBenchmark.chooseRecommendation(result);
        assertEquals(MediaCodecHelper.LOW_LATENCY_MODE_STANDARD, result.recommended.mode);
    }
}
