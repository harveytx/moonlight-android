package com.limelight.diagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Range;
import android.view.Display;

import com.limelight.binding.video.DecoderTuningStore;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.preferences.GlPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * Builds a plain-text report of everything that matters for low latency video on this
 * device: video decoders and their declared capabilities, the decisions Moonlight would
 * make for them, display modes, ALLM support, network link and SoC information.
 *
 * Every value comes from Android APIs or procfs/sysfs on the device itself. Nothing is
 * inferred from the model name, so the report is the ground truth to send back when a
 * device (such as the ZTE B866V2K) needs to be analysed.
 */
public final class DeviceCapabilityReport {
    private static final String[] VIDEO_MIMES = {"video/avc", "video/hevc", "video/av01"};
    private static final int[][] TEST_SIZES = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    private DeviceCapabilityReport() {
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String profileName(String mime, int profile) {
        if ("video/avc".equals(mime)) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline: return "Baseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline: return "ConstrainedBaseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh: return "High";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh: return "ConstrainedHigh";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10: return "High10";
                default: return "0x" + Integer.toHexString(profile);
            }
        }
        else if ("video/hevc".equals(mime)) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10: return "Main10";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10: return "Main10HDR10";
                default: return "0x" + Integer.toHexString(profile);
            }
        }
        return "0x" + Integer.toHexString(profile);
    }

    private static String avcLevelName(int level) {
        switch (level) {
            case MediaCodecInfo.CodecProfileLevel.AVCLevel31: return "3.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel32: return "3.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel4: return "4.0";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel41: return "4.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel42: return "4.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel5: return "5.0";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel51: return "5.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel52: return "5.2";
            default: return "0x" + Integer.toHexString(level);
        }
    }

    private static void appendDecoder(StringBuilder sb, Context context, MediaCodecInfo info, String mime) {
        sb.append("  ").append(info.getName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sb.append(info.isHardwareAccelerated() ? " [HW]" : " [SW?]");
            if (info.isSoftwareOnly()) {
                sb.append(" [software-only]");
            }
            if (info.isVendor()) {
                sb.append(" [vendor]");
            }
            if (!info.getCanonicalName().equals(info.getName())) {
                sb.append(" alias-of=").append(info.getCanonicalName());
            }
        }
        sb.append('\n');

        MediaCodecInfo.CodecCapabilities caps;
        try {
            caps = info.getCapabilitiesForType(mime);
        } catch (Exception e) {
            sb.append("    capabilities unavailable: ").append(e).append('\n');
            return;
        }

        StringBuilder profiles = new StringBuilder();
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            if (profiles.length() > 0) {
                profiles.append(", ");
            }
            profiles.append(profileName(mime, pl.profile));
            if ("video/avc".equals(mime)) {
                profiles.append("@L").append(avcLevelName(pl.level));
            }
            else {
                profiles.append("@0x").append(Integer.toHexString(pl.level));
            }
        }
        sb.append("    profiles/levels: ").append(profiles).append('\n');

        String[] features = {
                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
                MediaCodecInfo.CodecCapabilities.FEATURE_PartialFrame,
                "low-latency",
        };
        StringBuilder feat = new StringBuilder();
        for (String f : features) {
            try {
                if (caps.isFeatureSupported(f)) {
                    feat.append(f).append(' ');
                }
            } catch (Exception ignored) {
            }
        }
        sb.append("    features: ").append(feat.length() > 0 ? feat.toString().trim() : "(none)").append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sb.append("    max instances: ").append(caps.getMaxSupportedInstances()).append('\n');
        }

        MediaCodecInfo.VideoCapabilities vcaps = caps.getVideoCapabilities();
        if (vcaps != null) {
            sb.append("    widths: ").append(vcaps.getSupportedWidths())
                    .append(" heights: ").append(vcaps.getSupportedHeights())
                    .append(" alignment: ").append(vcaps.getWidthAlignment()).append('x').append(vcaps.getHeightAlignment())
                    .append('\n');
            sb.append("    bitrate range: ").append(vcaps.getBitrateRange()).append('\n');
            for (int[] size : TEST_SIZES) {
                if (!vcaps.isSizeSupported(size[0], size[1])) {
                    sb.append("    ").append(size[0]).append('x').append(size[1]).append(": not supported\n");
                    continue;
                }
                sb.append("    ").append(size[0]).append('x').append(size[1]).append(": supported fps ");
                try {
                    sb.append(vcaps.getSupportedFrameRatesFor(size[0], size[1]));
                } catch (Exception e) {
                    sb.append("?");
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> achievable = vcaps.getAchievableFrameRatesFor(size[0], size[1]);
                        sb.append(", achievable (measured by vendor) ").append(achievable != null ? achievable : "not published");
                    } catch (Exception e) {
                        sb.append(", achievable: n/a");
                    }
                }
                sb.append(", 60fps=").append(vcaps.areSizeAndRateSupported(size[0], size[1], 60));
                sb.append(", 120fps=").append(vcaps.areSizeAndRateSupported(size[0], size[1], 120));
                sb.append('\n');
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points = vcaps.getSupportedPerformancePoints();
                sb.append("    performance points: ");
                if (points == null || points.isEmpty()) {
                    sb.append("not published\n");
                }
                else {
                    for (MediaCodecInfo.VideoCapabilities.PerformancePoint p : points) {
                        sb.append(p).append(' ');
                    }
                    sb.append('\n');
                }
            }
        }

        // What Moonlight will decide for this decoder
        String name = info.getName();
        try {
            sb.append("    moonlight: FEATURE_LowLatency=").append(MediaCodecHelper.decoderAdvertisesLowLatency(info, mime))
                    .append(" directSubmit=").append(MediaCodecHelper.decoderCanDirectSubmit(name))
                    .append(" slices=").append(MediaCodecHelper.getDecoderOptimalSlicesPerFrame(name))
                    .append(" amlogic=").append(MediaCodecHelper.isAmlogicDecoder(name));
            if ("video/avc".equals(mime)) {
                sb.append(" rfiAvc(1080p)=").append(MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(name, 1080))
                        .append(" spsFixup=").append(MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(name))
                        .append(" constrainedHigh=").append(MediaCodecHelper.decoderNeedsConstrainedHighProfile(name));
            }
            else if ("video/hevc".equals(mime)) {
                sb.append(" hevcWhitelisted=").append(MediaCodecHelper.decoderIsWhitelistedForHevc(info));
            }
            sb.append('\n');
        } catch (Exception e) {
            sb.append("    moonlight heuristics unavailable: ").append(e).append('\n');
        }

        String tuned = DecoderTuningStore.getSummary(context, name, mime);
        if (tuned != null) {
            sb.append("    benchmark recommendation: ").append(tuned).append('\n');
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> params = MediaCodecHelper.getSupportedVendorParameters(name);
            sb.append("    vendor parameters (").append(params.size()).append("): ");
            for (String p : params) {
                sb.append(p).append(' ');
            }
            sb.append('\n');
        }
    }

    private static String connectionName(int type) {
        switch (type) {
            case 1:
                return "built-in";
            case 2:
                return "direct";
            case 3:
                return "transitive (via another device, e.g. AV receiver)";
            default:
                return "unknown";
        }
    }

    private static void appendDisplay(StringBuilder sb, Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        sb.append("\n== Display ==\n");
        sb.append("refresh rate: ").append(display.getRefreshRate()).append(" Hz\n");
        sb.append("app vsync offset: ").append(display.getAppVsyncOffsetNanos() / 1000).append(" us, presentation deadline: ")
                .append(display.getPresentationDeadlineNanos() / 1000).append(" us\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode current = display.getMode();
            sb.append("current mode: ").append(current.getPhysicalWidth()).append('x').append(current.getPhysicalHeight())
                    .append('@').append(current.getRefreshRate()).append('\n');
            sb.append("modes:");
            for (Display.Mode m : display.getSupportedModes()) {
                sb.append(' ').append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                        .append('@').append(String.format(Locale.ROOT, "%.2f", m.getRefreshRate()));
            }
            sb.append('\n');
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities hdr = display.getHdrCapabilities();
            sb.append("HDR types:");
            if (hdr != null) {
                for (int t : hdr.getSupportedHdrTypes()) {
                    sb.append(' ').append(t);
                }
            }
            sb.append('\n');
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.hardware.display.DeviceProductInfo info = display.getDeviceProductInfo();
            if (info != null) {
                sb.append("product (EDID): ").append(info.getName())
                        .append(", manufacturer PnP ID ").append(info.getManufacturerPnpId())
                        .append(", product ID ").append(info.getProductId())
                        .append(", year ").append(info.getModelYear() > 0 ? info.getModelYear() : info.getManufactureYear())
                        .append(", connection ").append(connectionName(info.getConnectionToSinkType())).append('\n');
            }
            else {
                sb.append("product (EDID): not reported\n");
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // True when the TV supports ALLM or HDMI Game Content Type, which removes the TV's
            // own post-processing latency. The streaming activity requests it in the manifest.
            // PC monitors normally don't advertise it and don't need it.
            sb.append("minimal post-processing (ALLM/Game Content Type) supported: ")
                    .append(display.isMinimalPostProcessingSupported()).append('\n');
        }
    }

    private static void appendNetwork(StringBuilder sb, Context context) {
        sb.append("\n== Network ==\n");
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            NetworkCapabilities nc = net != null ? cm.getNetworkCapabilities(net) : null;
            if (nc != null) {
                String transport = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" :
                        nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
                        nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "other";
                sb.append("transport: ").append(transport).append('\n');
                sb.append("link bandwidth estimate: down ").append(nc.getLinkDownstreamBandwidthKbps())
                        .append(" kbps, up ").append(nc.getLinkUpstreamBandwidthKbps()).append(" kbps\n");
            }
            LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp != null) {
                sb.append("interface: ").append(lp.getInterfaceName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sb.append(" mtu ").append(lp.getMtu());
                }
                sb.append('\n');
                String speed = lp.getInterfaceName() != null ?
                        readFirstLine("/sys/class/net/" + lp.getInterfaceName() + "/speed") : null;
                sb.append("link speed (sysfs): ").append(speed != null ? speed + " Mbps" : "not readable").append('\n');
            }
        }
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            if (wi != null && wi.getNetworkId() != -1) {
                sb.append("Wi-Fi link: ").append(wi.getLinkSpeed()).append(" Mbps, ")
                        .append(wi.getFrequency()).append(" MHz, RSSI ").append(wi.getRssi()).append(" dBm");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sb.append(", rx ").append(wi.getRxLinkSpeedMbps()).append(" Mbps");
                }
                sb.append('\n');
            }
        } catch (Exception ignored) {
        }
        sb.append("Note: a 100 Mbps Ethernet link adds ~0.08 ms per KB of frame data. At 20 Mbps/60 FPS a typical\n")
                .append("frame (~42 KB + FEC) needs ~4 ms on the wire; at 50 Mbps ~10 ms.\n");
    }

    private static void appendSystem(StringBuilder sb, Context context, String glRenderer) {
        sb.append("== System ==\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(", board ").append(Build.BOARD)
                .append(", hardware ").append(Build.HARDWARE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT)
                .append(" build ").append(Build.DISPLAY).append('\n');
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
            sb.append("media performance class: ").append(Build.VERSION.MEDIA_PERFORMANCE_CLASS).append('\n');
        }
        sb.append("ABIs: ");
        for (String abi : Build.SUPPORTED_ABIS) {
            sb.append(abi).append(' ');
        }
        sb.append('\n');
        sb.append("CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append(", max freq:");
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            String f = readFirstLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            sb.append(' ').append(f != null ? (Integer.parseInt(f) / 1000) + "MHz" : "?");
        }
        sb.append('\n');
        String governor = readFirstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
        sb.append("cpufreq governor: ").append(governor != null ? governor : "not readable").append('\n');

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append("RAM: ").append(mi.totalMem / (1024 * 1024)).append(" MB total, ")
                .append(mi.availMem / (1024 * 1024)).append(" MB available, low RAM device: ")
                .append(am.isLowRamDevice()).append('\n');
        sb.append("GL renderer: ").append(glRenderer).append('\n');

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sb.append("sustained performance mode supported: ").append(pm.isSustainedPerformanceModeSupported()).append('\n');
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sb.append("thermal status: ").append(pm.getCurrentThermalStatus()).append('\n');
        }
        sb.append("is TV: ").append(context.getPackageManager().hasSystemFeature("android.software.leanback")).append('\n');
    }

    public static String build(Activity activity) {
        GlPreferences glPrefs = GlPreferences.readPreferences(activity);
        MediaCodecHelper.initialize(activity, glPrefs.glRenderer);

        StringBuilder sb = new StringBuilder();
        sb.append("Moonlight ULL device capability report\n\n");
        appendSystem(sb, activity, glPrefs.glRenderer);
        appendDisplay(sb, activity);
        appendNetwork(sb, activity);

        sb.append("\n== Video decoders (hardware, as Moonlight sees them) ==\n");
        for (String mime : VIDEO_MIMES) {
            sb.append(mime).append(":\n");
            List<MediaCodecInfo> decoders = MediaCodecHelper.getHardwareDecodersForMime(mime);
            if (decoders.isEmpty()) {
                sb.append("  (none)\n");
            }
            for (MediaCodecInfo info : decoders) {
                appendDecoder(sb, activity, info, mime);
            }
        }

        sb.append("\n== All codecs (MediaCodecList.ALL_CODECS, decoders) ==\n");
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            sb.append("  ").append(info.getName()).append(':');
            for (String t : info.getSupportedTypes()) {
                if (t.startsWith("video/")) {
                    sb.append(' ').append(t);
                }
            }
            sb.append('\n');
        }

        File dir = SessionReport.getDirectory(activity);
        sb.append("\nSession reports directory: ").append(dir.getAbsolutePath()).append('\n');
        return sb.toString();
    }
}
