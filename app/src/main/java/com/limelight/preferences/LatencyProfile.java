package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * ULL performance profiles. A profile is just a named set of values for the preferences
 * that affect latency; applying it writes those values, so the individual settings remain
 * the single source of truth and can still be tweaked afterwards (the profile then shows
 * as "custom").
 *
 *                       ULL (default)  Balanced    Max quality  Original Moonlight
 *   Frame pacing        latency        balanced    balanced     latency
 *   Video codec         H.264          auto        auto         auto
 *   Decoder LL keys     auto           auto        auto         original
 *   Latency guard       aggressive     normal      normal       off
 *   Thread priority     high           high        normal       normal
 *   Bitrate             1.0x default   1.5x        2.5x         1.0x
 *
 * Resolution and FPS are not part of a profile: they depend on the display and decoder.
 */
public final class LatencyProfile {
    public static final String PREF_KEY = "ull_profile";

    public static final String ULTRA_LOW_LATENCY = "ull";
    public static final String BALANCED = "balanced";
    public static final String MAX_QUALITY = "quality";
    public static final String ORIGINAL = "original";
    public static final String CUSTOM = "custom";

    public static final String DEFAULT_PROFILE = ULTRA_LOW_LATENCY;

    // Preference keys managed by profiles
    static final String FRAME_PACING_KEY = "frame_pacing";
    static final String VIDEO_FORMAT_KEY = "video_format";
    static final String LOW_LATENCY_MODE_KEY = "ull_low_latency_mode";
    static final String LATENCY_GUARD_KEY = "ull_latency_guard";
    static final String THREAD_PRIORITY_KEY = "ull_thread_priority";

    static final String[] MANAGED_KEYS = {
            FRAME_PACING_KEY, VIDEO_FORMAT_KEY, LOW_LATENCY_MODE_KEY,
            LATENCY_GUARD_KEY, THREAD_PRIORITY_KEY, PreferenceConfiguration.BITRATE_PREF_STRING
    };

    private static final double MAX_BITRATE_KBPS = 150000;

    private static final class Values {
        final String framePacing, videoFormat, lowLatencyMode, latencyGuard, threadPriority;
        final double bitrateFactor;

        Values(String framePacing, String videoFormat, String lowLatencyMode,
               String latencyGuard, String threadPriority, double bitrateFactor) {
            this.framePacing = framePacing;
            this.videoFormat = videoFormat;
            this.lowLatencyMode = lowLatencyMode;
            this.latencyGuard = latencyGuard;
            this.threadPriority = threadPriority;
            this.bitrateFactor = bitrateFactor;
        }
    }

    private LatencyProfile() {
    }

    private static Values valuesFor(String profile) {
        switch (profile) {
            case BALANCED:
                return new Values("balanced", "auto", "auto", "normal", "high", 1.5);
            case MAX_QUALITY:
                return new Values("balanced", "auto", "auto", "normal", "normal", 2.5);
            case ORIGINAL:
                // Exactly what upstream Moonlight does with its default settings
                return new Values("latency", "auto", "original", "off", "normal", 1.0);
            case ULTRA_LOW_LATENCY:
            default:
                return new Values("latency", "neverh265", "auto", "aggressive", "high", 1.0);
        }
    }

    public static boolean isKnownProfile(String profile) {
        return ULTRA_LOW_LATENCY.equals(profile) || BALANCED.equals(profile) ||
                MAX_QUALITY.equals(profile) || ORIGINAL.equals(profile);
    }

    public static String getCurrentProfile(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(PREF_KEY, DEFAULT_PROFILE);
    }

    public static double getBitrateFactor(String profile) {
        if (!isKnownProfile(profile)) {
            return 1.0;
        }
        return valuesFor(profile).bitrateFactor;
    }

    static int computeBitrate(String profile, String resolution, String fps) {
        double kbps = PreferenceConfiguration.getDefaultBitrate(resolution, fps) * getBitrateFactor(profile);
        // The bitrate seekbar uses 500 Kbps steps
        return (int) (Math.round(Math.min(kbps, MAX_BITRATE_KBPS) / 500.0) * 500);
    }

    private static String currentResolution(SharedPreferences prefs) {
        return prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
    }

    private static String currentFps(SharedPreferences prefs) {
        return prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
    }

    /** Writes all values of a profile. Unknown profiles (e.g. "custom") only update the profile key. */
    public static void apply(Context context, String profile) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_KEY, profile);

        if (isKnownProfile(profile)) {
            Values v = valuesFor(profile);
            editor.putString(FRAME_PACING_KEY, v.framePacing)
                    .putString(VIDEO_FORMAT_KEY, v.videoFormat)
                    .putString(LOW_LATENCY_MODE_KEY, v.lowLatencyMode)
                    .putString(LATENCY_GUARD_KEY, v.latencyGuard)
                    .putString(THREAD_PRIORITY_KEY, v.threadPriority)
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            computeBitrate(profile, currentResolution(prefs), currentFps(prefs)));
        }

        // commit() so the settings UI reloads with the new values
        editor.commit();
    }

    /** True if the stored settings still match every value of the given profile. */
    public static boolean matches(Context context, String profile) {
        if (!isKnownProfile(profile)) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Values v = valuesFor(profile);
        return v.framePacing.equals(prefs.getString(FRAME_PACING_KEY, null)) &&
                v.videoFormat.equals(prefs.getString(VIDEO_FORMAT_KEY, null)) &&
                v.lowLatencyMode.equals(prefs.getString(LOW_LATENCY_MODE_KEY, null)) &&
                v.latencyGuard.equals(prefs.getString(LATENCY_GUARD_KEY, null)) &&
                v.threadPriority.equals(prefs.getString(THREAD_PRIORITY_KEY, null)) &&
                prefs.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1) ==
                        computeBitrate(profile, currentResolution(prefs), currentFps(prefs));
    }

    /**
     * Applies the default profile the first time the app runs, so a fresh install starts in
     * Ultra Low Latency mode rather than with Moonlight's original defaults.
     */
    public static void ensureInitialized(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.contains(PREF_KEY)) {
            apply(context, DEFAULT_PROFILE);
        }
    }

    /**
     * Restores defaults: removes every ULL advanced setting and re-applies the default profile.
     * Settings that are not latency related (gamepad, UI, ...) are left untouched.
     */
    public static void restoreDefaults(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(PreferenceConfiguration.ULL_AVC_DECODER_PREF_STRING)
                .remove(PreferenceConfiguration.ULL_DETAILED_OVERLAY_PREF_STRING)
                .remove(PreferenceConfiguration.ULL_RECORD_SESSIONS_PREF_STRING)
                .remove(PreferenceConfiguration.ULL_FRAME_LOG_PREF_STRING)
                .remove(PreferenceConfiguration.ULL_CAPTURE_INTERVAL_PREF_STRING)
                .commit();
        apply(context, DEFAULT_PROFILE);
    }
}
