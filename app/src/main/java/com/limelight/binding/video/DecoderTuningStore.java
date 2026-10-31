package com.limelight.binding.video;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;

/**
 * Persists the low latency configuration that the on-device decoder benchmark measured
 * as best for a given decoder and codec. The "auto" low latency mode uses it.
 *
 * Results are tied to the firmware fingerprint: a firmware update can change the vendor
 * codec implementation, so stale results are ignored until the benchmark is run again.
 */
public final class DecoderTuningStore {
    private static final String PREFS_NAME = "ull_decoder_tuning";

    private DecoderTuningStore() {
    }

    private static String key(String decoderName, String mimeType) {
        return decoderName.toLowerCase(Locale.ROOT) + "|" + mimeType.toLowerCase(Locale.ROOT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void save(Context context, String decoderName, String mimeType, int mode, String summary) {
        String k = key(decoderName, mimeType);
        prefs(context).edit()
                .putInt(k + "|mode", mode)
                .putString(k + "|fingerprint", Build.FINGERPRINT)
                .putLong(k + "|time", System.currentTimeMillis())
                .putString(k + "|summary", summary)
                .remove(k + "|visual")
                .apply();
    }

    /**
     * Records whether the user saw smooth playback (frame counter) during the benchmark.
     * Frame presentation reported by Android can't always detect a decoder that decodes at
     * full rate but only shows a few frames per second, so a "no" discards the result.
     */
    public static void setVisualCheck(Context context, String decoderName, String mimeType, boolean smooth) {
        String k = key(decoderName, mimeType);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (smooth) {
            editor.putBoolean(k + "|visual", true);
        }
        else {
            editor.remove(k + "|mode").remove(k + "|summary").remove(k + "|visual");
        }
        editor.apply();
    }

    public static boolean isVisuallyVerified(Context context, String decoderName, String mimeType) {
        String k = key(decoderName, mimeType);
        SharedPreferences p = prefs(context);
        return Build.FINGERPRINT.equals(p.getString(k + "|fingerprint", null)) &&
                p.getBoolean(k + "|visual", false);
    }

    /** @return the recommended MediaCodecHelper.LOW_LATENCY_MODE_* value, or -1 if none is stored */
    public static int getRecommendedMode(Context context, String decoderName, String mimeType) {
        String k = key(decoderName, mimeType);
        SharedPreferences p = prefs(context);
        if (!Build.FINGERPRINT.equals(p.getString(k + "|fingerprint", null))) {
            return -1;
        }
        return p.getInt(k + "|mode", -1);
    }

    public static String getSummary(Context context, String decoderName, String mimeType) {
        String k = key(decoderName, mimeType);
        SharedPreferences p = prefs(context);
        if (!Build.FINGERPRINT.equals(p.getString(k + "|fingerprint", null))) {
            return null;
        }
        return p.getString(k + "|summary", null);
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
