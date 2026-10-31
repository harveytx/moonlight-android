package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * ULL: warnings about settings or hardware that add latency outside of Moonlight's control,
 * shown when a stream starts (respecting "Disable warning messages").
 */
public final class StreamWarnings {
    private static final String ALLM_WARNING_SHOWN_PREF = "ull_allm_warning_shown";
    // Sunshine's default FEC percentage
    private static final double FEC_OVERHEAD = 1.2;
    // Warn when the stream with FEC uses more than this fraction of the link
    private static final double LINK_USAGE_WARNING = 0.6;

    private StreamWarnings() {
    }

    /** True if the display (TV) reports support for ALLM / HDMI Game Content Type. */
    public static boolean isMinimalPostProcessingSupported(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true; // Unknown: don't warn
        }
        return activity.getWindowManager().getDefaultDisplay().isMinimalPostProcessingSupported();
    }

    /** Link speed in kbps if the active network is wired Ethernet, otherwise -1. */
    private static int getEthernetLinkKbps(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return -1;
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return -1;
        }
        return caps.getLinkDownstreamBandwidthKbps();
    }

    public static List<String> getStartupWarnings(Activity activity, PreferenceConfiguration prefs) {
        List<String> warnings = new ArrayList<>();

        int linkKbps = getEthernetLinkKbps(activity);
        if (linkKbps > 0 && prefs.bitrate * FEC_OVERHEAD > LINK_USAGE_WARNING * linkKbps) {
            // Average time on the wire for one frame at this bitrate (including FEC)
            double frameMs = prefs.bitrate * FEC_OVERHEAD / prefs.fps / linkKbps * 1000.0;
            warnings.add(activity.getString(R.string.ull_warning_bitrate_link,
                    prefs.bitrate / 1000, linkKbps / 1000, frameMs));
        }

        if (!isMinimalPostProcessingSupported(activity)) {
            // Only once: it's a property of the TV, not of a setting
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
            if (!sp.getBoolean(ALLM_WARNING_SHOWN_PREF, false)) {
                sp.edit().putBoolean(ALLM_WARNING_SHOWN_PREF, true).apply();
                warnings.add(activity.getString(R.string.ull_warning_no_allm));
            }
        }

        return warnings;
    }
}
