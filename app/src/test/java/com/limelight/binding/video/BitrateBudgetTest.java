package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BitrateBudgetTest {
    @Test
    public void encoderBitrateFollowsSunshineAccounting() {
        // 80 Mbps, FEC 20 %, stereo: 80000 * 0.8 = 64000, - 192 audio, - 500 overhead
        assertEquals(63308, BitrateBudget.sunshineEncoderKbps(80000, 20, 2));
        assertEquals(39308, BitrateBudget.sunshineEncoderKbps(50000, 20, 2));
        assertEquals(15308, BitrateBudget.sunshineEncoderKbps(20000, 20, 2));
        // FEC 10 % leaves more for the encoder
        assertEquals(71308, BitrateBudget.sunshineEncoderKbps(80000, 10, 2));
        // FEC above 80 % is not taken out of the bitrate
        assertEquals(9308, BitrateBudget.sunshineEncoderKbps(10000, 90, 2));
    }

    @Test
    public void surroundUsesHighQualityAudioFrom15Mbps() {
        // 5.1 at 20 Mbps: 256 kbps x 6 channels
        assertEquals(16000 - 1536 - 500, BitrateBudget.sunshineEncoderKbps(20000, 20, 6));
        // 5.1 at 10 Mbps: normal quality, 96 kbps x 6 channels
        assertEquals(8000 - 576 - 500, BitrateBudget.sunshineEncoderKbps(10000, 20, 6));
    }

    @Test
    public void adjustmentsAreCappedAtLowBitrates() {
        // 1 Mbps: 800, audio capped at 20 % (160), overhead capped at 10 % (64)
        assertEquals(576, BitrateBudget.sunshineEncoderKbps(1000, 20, 2));
    }

    @Test
    public void frameCeilingIsOneFrameOfEncoderBitrate() {
        assertEquals(131891, BitrateBudget.sunshineFrameCeilingBytes(80000, 60, 2));
        assertEquals(81891, BitrateBudget.sunshineFrameCeilingBytes(50000, 60, 2));
        assertEquals(31891, BitrateBudget.sunshineFrameCeilingBytes(20000, 60, 2));
        assertEquals(0, BitrateBudget.frameCeilingBytes(20000, 0));
    }

    @Test
    public void transferTimeScalesWithLinkSpeed() {
        // Measured on the ZTE: 0.086 ms per kB at 100 Mbps
        assertEquals(11.34, BitrateBudget.estimateTransferMs(131891, 100000), 0.01);
        assertEquals(2.74, BitrateBudget.estimateTransferMs(31891, 100000), 0.01);
        assertEquals(1.134, BitrateBudget.estimateTransferMs(131891, 1000000), 0.001);
        assertEquals(0, BitrateBudget.estimateTransferMs(131891, 0), 0);
    }
}
