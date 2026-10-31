package com.limelight.binding.video;

/**
 * ULL: what a configured bitrate means for each video frame when streaming from Sunshine.
 *
 * Sunshine treats the bitrate configured in the client as the total for video + FEC + audio +
 * overhead (src/rtsp.cpp, cmd_announce): it removes the FEC share, the audio bitrate (capped
 * at 20 %) and 500 kbps (capped at 10 %), and gives the rest to the encoder. Its encoders use a
 * VBV/HRD buffer of one frame (bitrate / fps; src/nvenc/nvenc_base.cpp, src/video.cpp), so no
 * frame can be larger than that: a frame "at the ceiling" is one where the encoder wanted more
 * bits than the bitrate allowed. Raising the bitrate only improves frames that hit the ceiling.
 *
 * The client cannot see Sunshine's fec_percentage or nvenc_vbv_increase, so the ceiling is an
 * estimate for the defaults (FEC 20 %, no VBV increase). Pure Java so it can be unit tested.
 */
public final class BitrateBudget {
    /** Sunshine's default fec_percentage. */
    public static final int SUNSHINE_DEFAULT_FEC_PERCENT = 20;

    /**
     * Receive time per kB (1000 bytes) of frame data measured on the ZTE B866V2K over its
     * 100 Mbps Ethernet port (linear fit over the sessions of 12.2-ULL.2 to ULL.5, FEC 20 %):
     * frames arrive at line rate, so the time scales with the link speed.
     */
    public static final double MEASURED_MS_PER_KB_AT_100_MBPS = 0.086;

    // moonlight-common-c only requests high quality audio for surround at >= 15 Mbps
    private static final int HIGH_QUALITY_AUDIO_MIN_KBPS = 15000;

    private BitrateBudget() {
    }

    /** Video encoder bitrate (kbps) Sunshine derives from the bitrate configured in the client. */
    public static long sunshineEncoderKbps(long configuredKbps, int fecPercent, int audioChannels) {
        long kbps = configuredKbps;
        if (fecPercent <= 80) {
            // configuredBitrateKbps /= 100.f / (100 - fec_percentage)
            kbps = (long) (kbps / (100.0f / (100 - fecPercent)));
        }
        boolean highQualityAudio = audioChannels > 2 && configuredKbps >= HIGH_QUALITY_AUDIO_MIN_KBPS;
        long audioKbps = (highQualityAudio ? 256L : 96L) * audioChannels;
        kbps -= Math.min(audioKbps, kbps / 5);
        kbps -= Math.min(500L, kbps / 10);
        return Math.max(kbps, 0);
    }

    /** Largest frame (bytes) a one-frame VBV buffer allows at this encoder bitrate. */
    public static int frameCeilingBytes(long encoderKbps, int fps) {
        if (fps <= 0) {
            return 0;
        }
        return (int) (encoderKbps * 1000L / 8L / fps);
    }

    /** Frame ceiling (bytes) for a configured bitrate with Sunshine's default settings. */
    public static int sunshineFrameCeilingBytes(long configuredKbps, int fps, int audioChannels) {
        return frameCeilingBytes(sunshineEncoderKbps(configuredKbps, SUNSHINE_DEFAULT_FEC_PERCENT, audioChannels), fps);
    }

    /**
     * Estimated time (ms) from the first to the last packet of a frame of this size on an
     * Ethernet link, scaled from the measurement on the 100 Mbps link of the ZTE.
     */
    public static double estimateTransferMs(int frameBytes, long linkKbps) {
        if (linkKbps <= 0) {
            return 0;
        }
        return frameBytes / 1000.0 * MEASURED_MS_PER_KB_AT_100_MBPS * (100000.0 / linkKbps);
    }
}
