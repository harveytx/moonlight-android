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
import com.limelight.binding.video.BitrateBudget;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * ULL: warnings about settings or hardware that add latency outside of Moonlight's control,
 * shown when a stream starts (respecting "Disable warning messages").
 */
public final class StreamWarnings {
    private static final String ALLM_WARNING_SHOWN_PREF = "ull_allm_warning_shown";
    // Warn when the stream can use more than this fraction of the link. Sunshine fits video, FEC,
    // audio and overhead inside the configured bitrate (see BitrateBudget), so in complex scenes
    // the stream uses about the configured bitrate on the wire.
    private static final double LINK_USAGE_WARNING = 0.6;
    // Reference bitrate for the comparison in the warning (Moonlight's default for 1080p60)
    private static final int REFERENCE_BITRATE_KBPS = 20000;

    private StreamWarnings() {
    }

    /** True if the display (TV) reports support for ALLM / HDMI Game Content Type. */
    public static boolean isMinimalPostProcessingSupported(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true; // Unknown: don't warn
        }
        return activity.getWindowManager().getDefaultDisplay().isMinimalPostProcessingSupported();
    }

    /** Product name of the connected display from its EDID (Android 12+), or null. */
    public static String getDisplayProductName(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        android.hardware.display.DeviceProductInfo info =
                activity.getWindowManager().getDefaultDisplay().getDeviceProductInfo();
        if (info == null || info.getName() == null || info.getName().trim().isEmpty()) {
            return null;
        }
        return info.getName().trim();
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
        if (linkKbps > 0 && prefs.bitrate > LINK_USAGE_WARNING * linkKbps) {
            // Time to receive a frame of the largest size the host can send at this bitrate,
            // from the receive time measured per kB on the ZTE's 100 Mbps port
            int channels = prefs.audioConfiguration != null ? prefs.audioConfiguration.channelCount : 2;
            int ceilingBytes = BitrateBudget.sunshineFrameCeilingBytes(prefs.bitrate, prefs.fps, channels);
            int referenceBytes = BitrateBudget.sunshineFrameCeilingBytes(REFERENCE_BITRATE_KBPS, prefs.fps, channels);
            warnings.add(activity.getString(R.string.ull_warning_bitrate_link,
                    prefs.bitrate / 1000, linkKbps / 1000, ceilingBytes / 1000.0,
                    BitrateBudget.estimateTransferMs(ceilingBytes, linkKbps),
                    BitrateBudget.estimateTransferMs(referenceBytes, linkKbps)));
        }

        if (!isMinimalPostProcessingSupported(activity)) {
            // Only once: it's a property of the TV, not of a setting
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
            if (!sp.getBoolean(ALLM_WARNING_SHOWN_PREF, false)) {
                sp.edit().putBoolean(ALLM_WARNING_SHOWN_PREF, true).apply();
                String name = getDisplayProductName(activity);
                warnings.add(activity.getString(R.string.ull_warning_no_allm,
                        name != null ? name : activity.getString(R.string.ull_display_unknown)));
            }
        }

        return warnings;
    }
}
