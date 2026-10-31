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
}
