package com.limelight.diagnostics;

import android.content.Context;
import android.os.Build;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.PipelineStats;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stores a summary of every streaming session (and optionally a per-frame CSV) so that
 * different configurations can be compared later under the same conditions.
 *
 * Files live in the app-specific external storage directory, which needs no permission and
 * can be pulled with: adb pull /sdcard/Android/data/<package>/files/ull-sessions
 *
 * Every app version writes into its own subfolder (ull-sessions/<versionName>/) so results of
 * different builds are never mixed up. Files written by versions before 12.2-ULL.5 directly
 * into ull-sessions/ are moved once: session reports into the folder of the version recorded
 * inside them, everything else (which has no version information) into "unversioned".
 */
public final class SessionReport {
    private static final String DIRECTORY = "ull-sessions";
    private static final int MIN_FRAMES_FOR_REPORT = 60;

    private SessionReport() {
    }

    private static final String UNVERSIONED_DIRECTORY = "unversioned";
    private static boolean legacyFilesMigrated;

    /** ull-sessions/, which contains one folder per app version. */
    public static File getRootDirectory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, DIRECTORY);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Folder of the running app version, where new reports are written. */
    public static File getDirectory(Context context) {
        File root = getRootDirectory(context);
        migrateLegacyFiles(root);
        File dir = new File(root, versionFolderName(BuildConfig.VERSION_NAME));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    static String versionFolderName(String versionName) {
        if (versionName == null || versionName.isEmpty()) {
            return UNVERSIONED_DIRECTORY;
        }
        // Keep it a single, portable path component
        return versionName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // Moves report files that older versions wrote directly into ull-sessions/
    private static synchronized void migrateLegacyFiles(File root) {
        if (legacyFilesMigrated) {
            return;
        }
        legacyFilesMigrated = true;

        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.endsWith(".json") || !name.startsWith("session-")) {
                continue;
            }
            String version = UNVERSIONED_DIRECTORY;
            try {
                version = versionFolderName(load(f).optString("app_version", ""));
            } catch (IOException | JSONException e) {
                LimeLog.warning("Unreadable session report " + name + ": " + e);
            }
            File target = new File(root, version);
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(new File(target, name));
            File csv = new File(root, name.substring(0, name.length() - ".json".length()) + "-frames.csv");
            if (csv.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                csv.renameTo(new File(target, csv.getName()));
            }
        }

        // Capability and benchmark reports of older versions carry no version information
        files = root.listFiles();
        if (files == null) {
            return;
        }
        File unversioned = new File(root, UNVERSIONED_DIRECTORY);
        for (File f : files) {
            if (f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                unversioned.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(new File(unversioned, f.getName()));
            }
        }
    }

    private static JSONObject stageJson(PipelineStats.StageSummary s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("count", s.count);
        o.put("mean_ms", round(s.meanMs));
        o.put("min_ms", round(s.minMs));
        o.put("p50_ms", round(s.p50Ms));
        o.put("p95_ms", round(s.p95Ms));
        o.put("p99_ms", round(s.p99Ms));
        o.put("max_ms", round(s.maxMs));
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * Saves the report of a finished session. Must be called after the decoder has been
     * stopped so the statistics are final.
     *
     * @return the report file, or null if the session was too short or saving failed
     */
    public static File save(Context context, MediaCodecDecoderRenderer renderer,
                            PreferenceConfiguration prefs, String hostName, String appName) {
        PipelineStats stats = renderer.getPipelineStats();
        PipelineStats.Snapshot snap = stats.getSessionSnapshot(System.nanoTime());
        if (snap.counters.framesReceived < MIN_FRAMES_FOR_REPORT) {
            return null;
        }

        try {
            JSONObject root = new JSONObject();
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            root.put("format", "moonlight-ull-session-1");
            root.put("timestamp", timestamp);
            root.put("app_version", BuildConfig.VERSION_NAME);
            root.put("host", hostName != null ? hostName : "");
            root.put("app", appName != null ? appName : "");

            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("device", Build.DEVICE);
            device.put("hardware", Build.HARDWARE);
            device.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.put("soc", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
            }
            root.put("device", device);

            JSONObject settings = new JSONObject();
            settings.put("profile", prefs.latencyProfile);
            settings.put("resolution", prefs.width + "x" + prefs.height);
            settings.put("fps", prefs.fps);
            settings.put("bitrate_kbps", prefs.bitrate);
            settings.put("frame_pacing", prefs.framePacing);
            settings.put("video_format_pref", String.valueOf(prefs.videoFormat));
            settings.put("low_latency_mode_pref", prefs.lowLatencyMode);
            settings.put("latency_guard_pref", prefs.latencyGuardMode);
            settings.put("thread_priority_pref", prefs.threadPriorityLevel);
            root.put("settings", settings);

            JSONObject decoder = new JSONObject();
            for (Map.Entry<String, String> e : renderer.getDecoderDiagnostics().entrySet()) {
                decoder.put(e.getKey(), e.getValue());
            }
            root.put("decoder", decoder);

            JSONObject stages = new JSONObject();
            for (int i = 0; i < PipelineStats.STAGE_COUNT; i++) {
                stages.put(PipelineStats.STAGE_KEYS[i], stageJson(snap.stages[i]));
            }
            root.put("stages_ms", stages);

            JSONObject counters = new JSONObject();
            counters.put("duration_s", round(snap.durationSec));
            counters.put("frames_received", snap.counters.framesReceived);
            counters.put("frames_lost_network", snap.counters.framesLostNetwork);
            counters.put("loss_events", snap.counters.lossEvents);
            counters.put("frames_decoded", snap.counters.framesDecoded);
            counters.put("frames_rendered", snap.counters.framesRendered);
            counters.put("frames_dropped_render", snap.counters.framesDroppedRender);
            counters.put("frames_displayed_reported", snap.counters.framesDisplayed);
            counters.put("resyncs", snap.counters.resyncs);
            counters.put("received_fps", round(snap.fps(snap.counters.framesReceived)));
            counters.put("rendered_fps", round(snap.fps(snap.counters.framesRendered)));
            counters.put("network_loss_percent", round(snap.networkLossPercent()));
            counters.put("render_drop_percent", round(snap.renderDropPercent()));
            counters.put("jitter_ms", round(snap.jitterMs));
            counters.put("bitrate_mbps", round(snap.bitrateMbps()));
            root.put("counters", counters);

            // ULL: frame sizes against the largest frame the host can send at this bitrate.
            // Only frames at that ceiling would improve with a higher bitrate.
            JSONObject frameSize = new JSONObject();
            if (snap.frameSizeKb != null && snap.frameSizeKb.count > 0) {
                frameSize.put("count", snap.frameSizeKb.count);
                frameSize.put("mean_kb", round(snap.frameSizeKb.meanMs));
                frameSize.put("p50_kb", round(snap.frameSizeKb.p50Ms));
                frameSize.put("p95_kb", round(snap.frameSizeKb.p95Ms));
                frameSize.put("p99_kb", round(snap.frameSizeKb.p99Ms));
                frameSize.put("max_kb", round(snap.frameSizeKb.maxMs));
            }
            if (snap.frameCeilingBytes > 0) {
                frameSize.put("ceiling_kb_estimate", round(snap.frameCeilingBytes / 1000.0));
                frameSize.put("at_ceiling_percent", round(snap.nearCeilingPercent()));
            }
            root.put("frame_size_kb", frameSize);

            String baseName = "session-" + timestamp + "-" + prefs.latencyProfile;
            File out = new File(getDirectory(context), baseName + ".json");
            writeString(out, root.toString(2));

            PipelineStats.FrameLog frameLog = stats.getFrameLog();
            if (frameLog != null && frameLog.size() > 0) {
                File csv = new File(getDirectory(context), baseName + "-frames.csv");
                try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(csv), StandardCharsets.UTF_8))) {
                    frameLog.writeCsv(w);
                }
            }

            LimeLog.info("ULL session report saved: " + out.getAbsolutePath());
            return out;
        } catch (JSONException | IOException e) {
            LimeLog.warning("Unable to save session report: " + e);
            return null;
        }
    }

    static void writeString(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String readString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return new String(data, 0, off, StandardCharsets.UTF_8);
        }
    }

    /** Session reports, newest first. */
    /** Session reports of all versions, newest first (A/B comparisons may span versions). */
    public static List<File> list(Context context) {
        getDirectory(context); // Migrates legacy files if needed
        List<File> result = new ArrayList<>();
        File[] versionDirs = getRootDirectory(context).listFiles();
        if (versionDirs == null) {
            return result;
        }
        for (File dir : versionDirs) {
            File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f.getName().startsWith("session-") && f.getName().endsWith(".json")) {
                    result.add(f);
                }
            }
        }
        // File names start with the timestamp, so this sorts by time across folders
        java.util.Collections.sort(result, (a, b) -> b.getName().compareTo(a.getName()));
        return result;
    }

    public static JSONObject load(File file) throws IOException, JSONException {
        return new JSONObject(readString(file));
    }

    public static String describe(JSONObject report) {
        JSONObject settings = report.optJSONObject("settings");
        JSONObject decoder = report.optJSONObject("decoder");
        StringBuilder sb = new StringBuilder();
        sb.append(report.optString("timestamp"))
                .append("  v").append(report.optString("app_version", "?"))
                .append("  [").append(settings != null ? settings.optString("profile") : "?").append("]  ");
        if (settings != null) {
            sb.append(settings.optString("resolution")).append('@').append(settings.optInt("fps"))
                    .append(' ').append(settings.optInt("bitrate_kbps") / 1000.0).append(" Mbps");
        }
        if (decoder != null) {
            sb.append("  ").append(decoder.optString("decoder"));
        }
        return sb.toString();
    }

    // Metrics shown in reports and comparisons: label resource, JSON path, lower-is-better
    private static final Object[][] METRICS = {
            {R.string.ull_metric_received_fps, "counters/received_fps", false},
            {R.string.ull_metric_rendered_fps, "counters/rendered_fps", false},
            {R.string.ull_metric_network_loss, "counters/network_loss_percent", true},
            {R.string.ull_metric_render_drops, "counters/render_drop_percent", true},
            {R.string.ull_metric_jitter, "counters/jitter_ms", true},
            {R.string.ull_metric_queue_delay_p95, "stages_ms/queue_delay/p95_ms", true},
            {R.string.ull_metric_receive, "stages_ms/receive/mean_ms", true},
            {R.string.ull_metric_submit, "stages_ms/submit/mean_ms", true},
            {R.string.ull_metric_jni, "stages_ms/jni/mean_ms", true},
            {R.string.ull_metric_decode, "stages_ms/decode/mean_ms", true},
            {R.string.ull_metric_decode_p95, "stages_ms/decode/p95_ms", true},
            {R.string.ull_metric_pacing, "stages_ms/pacing/mean_ms", true},
            {R.string.ull_metric_display, "stages_ms/display/mean_ms", true},
            {R.string.ull_metric_client, "stages_ms/client/mean_ms", true},
            {R.string.ull_metric_client_p95, "stages_ms/client/p95_ms", true},
            {R.string.ull_metric_client_display, "stages_ms/client_display/mean_ms", true},
            {R.string.ull_metric_host, "stages_ms/host/mean_ms", true},
            {R.string.ull_metric_resyncs, "counters/resyncs", true},
            // No better/worse verdict: larger frames cost receive time but carry more detail
            {R.string.ull_metric_frame_size_p95, "frame_size_kb/p95_kb", null},
            {R.string.ull_metric_at_ceiling, "frame_size_kb/at_ceiling_percent", null},
    };

    private static Double lookup(JSONObject root, String path) {
        String[] parts = path.split("/");
        JSONObject cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            cur = cur.optJSONObject(parts[i]);
            if (cur == null) {
                return null;
            }
        }
        String last = parts[parts.length - 1];
        if (!cur.has(last)) {
            return null;
        }
        // Stages with no samples (e.g. display time not reported) are not comparable
        if (parts.length == 3 && cur.optLong("count", 0) == 0) {
            return null;
        }
        return cur.optDouble(last);
    }

    private static String fmt(Double v) {
        return v == null ? "n/a" : String.format(Locale.ROOT, "%.2f", v);
    }

    public static String toReadableText(Context context, JSONObject report) {
        StringBuilder sb = new StringBuilder();
        sb.append(describe(report)).append("\n\n");
        for (Object[] m : METRICS) {
            sb.append(String.format(Locale.ROOT, "%-34s %10s%n",
                    context.getString((Integer) m[0]), fmt(lookup(report, (String) m[1]))));
        }
        JSONObject decoder = report.optJSONObject("decoder");
        if (decoder != null) {
            sb.append('\n');
            Iterator<String> keys = decoder.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                sb.append(k).append(": ").append(decoder.optString(k)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Side-by-side comparison of two sessions (B relative to A). */
    public static String compare(Context context, JSONObject a, JSONObject b) {
        StringBuilder sb = new StringBuilder();
        sb.append("A: ").append(describe(a)).append('\n');
        sb.append("B: ").append(describe(b)).append("\n\n");
        sb.append(String.format(Locale.ROOT, "%-34s %9s %9s %9s%n", "", "A", "B", "B-A"));
        for (Object[] m : METRICS) {
            Double va = lookup(a, (String) m[1]);
            Double vb = lookup(b, (String) m[1]);
            String delta = "n/a";
            if (va != null && vb != null) {
                double d = vb - va;
                Boolean lowerIsBetter = (Boolean) m[2];
                String verdict = "";
                if (lowerIsBetter != null && Math.abs(d) >= 0.005) {
                    boolean better = lowerIsBetter ? d < 0 : d > 0;
                    verdict = better ? " +" : " -";
                }
                delta = String.format(Locale.ROOT, "%+.2f%s", d, verdict);
            }
            sb.append(String.format(Locale.ROOT, "%-34s %9s %9s %9s%n",
                    context.getString((Integer) m[0]), fmt(va), fmt(vb), delta));
        }
        sb.append('\n').append(context.getString(R.string.ull_compare_legend));
        return sb.toString();
    }
}
