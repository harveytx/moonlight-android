package com.limelight.diagnostics;

import android.app.Activity;
import android.view.SurfaceHolder;

import com.limelight.LimeLog;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.DecoderTuningStore;
import com.limelight.binding.video.LatencyGuard;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.PipelineStats;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * On-device decoder benchmark.
 *
 * Replays a synthetic Sunshine-like stream (assets/bench) through the real
 * {@link MediaCodecDecoderRenderer}, exactly as moonlight-common-c would deliver it, on a
 * real SurfaceView. Each MediaCodec low latency strategy is measured with the same
 * per-stage instrumentation used during streaming, so the results are directly comparable
 * and nothing about the decoder's behaviour has to be assumed.
 *
 * Network effects are excluded by design: frames are "received" when they are submitted.
 * The decode stage (queueInputBuffer -> dequeueOutputBuffer) and the display stage are
 * what this measures.
 */
public class DecoderBenchmark {
    public interface Listener {
        void onProgress(String text);

        void onFinished(Result result);
    }

    public static class ConfigResult {
        public int mode;
        public String keys = "";
        public int rounds;
        public double decodeMeanMs, decodeP50Ms, decodeP95Ms, decodeMaxMs;
        public double submitMeanMs;
        public double decodedPercent;
        public double displayedFps = -1;
        public double displayMeanMs = -1;
        public int idrRequests;
        public boolean valid = true;
        public String error;
        // p95 decode time of each round, in execution order of the rounds
        public final List<Double> roundP95Ms = new ArrayList<>();
    }

    public static class Result {
        public String decoderName = "(none)";
        public String mime;
        public int width, height, fps;
        public final List<ConfigResult> configs = new ArrayList<>();
        public ConfigResult recommended;
        public String recommendationReason = "";
        public double maxThroughputFps = -1;
        public String report;
        public File reportFile;
    }

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FPS = 60;
    private static final int WARMUP_FRAMES = 60;
    private static final int MEASURE_FRAMES = 300;
    private static final int THROUGHPUT_FRAMES = 600;
    // Three interleaved rounds: the first ZTE measurements showed ~0.6 ms of variation between
    // runs of the same configuration, more than the 0.5 ms recommendation threshold
    private static final int ROUNDS = 3;
    private static final int[] MODES = {
            MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL,
            MediaCodecHelper.LOW_LATENCY_MODE_STANDARD,
            MediaCodecHelper.LOW_LATENCY_MODE_VENDOR,
            MediaCodecHelper.LOW_LATENCY_MODE_OFF,
    };

    private final Activity activity;
    private final SurfaceHolder holder;
    private final boolean hevc;
    private final float displayRefreshRate;
    private volatile boolean cancelled;
    private volatile String rendererFailure;

    public DecoderBenchmark(Activity activity, SurfaceHolder holder, boolean hevc, float displayRefreshRate) {
        this.activity = activity;
        this.holder = holder;
        this.hevc = hevc;
        this.displayRefreshRate = displayRefreshRate;
    }

    public void cancel() {
        cancelled = true;
    }

    private PreferenceConfiguration benchmarkPrefs(int mode) {
        PreferenceConfiguration p = PreferenceConfiguration.readPreferences(activity);
        p.width = WIDTH;
        p.height = HEIGHT;
        p.fps = FPS;
        p.bitrate = 15000;
        p.videoFormat = hevc ? PreferenceConfiguration.FormatOption.FORCE_HEVC : PreferenceConfiguration.FormatOption.FORCE_H264;
        p.enableHdr = false;
        p.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        p.lowLatencyMode = mode;
        p.latencyGuardMode = LatencyGuard.MODE_OFF;
        p.enablePerfOverlay = false;
        p.detailedPerfOverlay = false;
        // Enables the system-reported display time (OnFrameRenderedListener)
        p.recordSessions = true;
        p.recordFrameLog = false;
        return p;
    }

    private static int submitAccessUnit(MediaCodecDecoderRenderer renderer, AnnexBClip.AccessUnit au,
                                        int frameNumber, long ptsUs) {
        long nowUs = System.nanoTime() / 1000;
        for (AnnexBClip.ParameterSet ps : au.parameterSets) {
            int ret = renderer.submitDecodeUnit(ps.data, ps.data.length, ps.bufferType, frameNumber,
                    MoonBridge.FRAME_TYPE_IDR, (char) 0, nowUs, nowUs, ptsUs, System.nanoTime() / 1000);
            if (ret != MoonBridge.DR_OK) {
                return ret;
            }
        }
        return renderer.submitDecodeUnit(au.pictureData, au.pictureData.length, MoonBridge.BUFFER_TYPE_PICDATA,
                frameNumber, au.keyFrame ? MoonBridge.FRAME_TYPE_IDR : MoonBridge.FRAME_TYPE_PFRAME,
                (char) 0, nowUs, nowUs, ptsUs, System.nanoTime() / 1000);
    }

    // Feeds frames; returns the number of DR_NEED_IDR results
    private int feed(MediaCodecDecoderRenderer renderer, AnnexBClip clip, int[] cursor, int frames, boolean paced) {
        long intervalNs = 1_000_000_000L / FPS;
        long next = System.nanoTime();
        int needIdr = 0;
        for (int i = 0; i < frames && !cancelled && rendererFailure == null; i++) {
            // cursor[0] = clip position, cursor[1] = frame number
            AnnexBClip.AccessUnit au = clip.accessUnits.get(cursor[0]);
            long ptsUs = (long) cursor[1] * 1_000_000L / FPS;
            int ret = submitAccessUnit(renderer, au, cursor[1], ptsUs);
            cursor[1]++;
            if (ret == MoonBridge.DR_NEED_IDR) {
                // Like the host would, restart from the IDR frame at the start of the clip
                needIdr++;
                cursor[0] = 0;
            }
            else {
                cursor[0] = (cursor[0] + 1) % clip.accessUnits.size();
            }

            if (paced) {
                next += intervalNs;
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    LockSupport.parkNanos(sleepNs);
                }
                else if (sleepNs < -4 * intervalNs) {
                    // Don't try to catch up on a large backlog
                    next = System.nanoTime();
                }
            }
        }
        return needIdr;
    }

    // Waits until the decoder stops producing frames; returns the time of the last change
    private static long waitForDrain(PipelineStats stats) {
        long lastCount = -1;
        long lastChange = System.nanoTime();
        long deadline = lastChange + 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            long count = stats.getTotalFramesDecoded();
            if (count != lastCount) {
                lastCount = count;
                lastChange = System.nanoTime();
            }
            else if (System.nanoTime() - lastChange > 150_000_000L) {
                break;
            }
            LockSupport.parkNanos(10_000_000L);
        }
        return lastChange;
    }

    private MediaCodecDecoderRenderer createRenderer(int mode, String glRenderer, boolean discardOutput) {
        PreferenceConfiguration prefs = benchmarkPrefs(mode);
        prefs.benchmarkDiscardOutput = discardOutput;
        MediaCodecDecoderRenderer renderer = new MediaCodecDecoderRenderer(activity, prefs,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        rendererFailure = String.valueOf(e);
                    }
                }, 0, false, false, glRenderer, new PerfOverlayListener() {
                    @Override
                    public void onPerfUpdate(String text) {
                    }
                });
        renderer.setRenderTarget(holder);
        return renderer;
    }

    // Only call after a successful setup(); 'started' tells whether start() was called
    private static void stopRenderer(MediaCodecDecoderRenderer renderer, boolean started) {
        try {
            renderer.prepareForStop();
            if (started) {
                renderer.stop();
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Benchmark renderer stop failed: " + e);
        }
        try {
            renderer.cleanup();
        } catch (RuntimeException e) {
            LimeLog.warning("Benchmark renderer cleanup failed: " + e);
        }
    }

    private ConfigResult runConfig(AnnexBClip clip, int mode, String glRenderer, Result result) {
        ConfigResult cr = new ConfigResult();
        cr.mode = mode;
        rendererFailure = null;

        MediaCodecDecoderRenderer renderer = createRenderer(mode, glRenderer, false);
        int format = hevc ? MoonBridge.VIDEO_FORMAT_H265 : MoonBridge.VIDEO_FORMAT_H264;
        if (hevc && !renderer.isHevcSupported()) {
            cr.valid = false;
            cr.error = "no HEVC decoder";
            return cr;
        }

        try {
            // On failure setup() has already released the codec, so there is nothing to clean up
            int err = renderer.setup(format, WIDTH, HEIGHT, FPS);
            if (err != 0) {
                cr.valid = false;
                cr.error = "setup failed (" + err + ")";
                return cr;
            }
        } catch (RuntimeException e) {
            cr.valid = false;
            cr.error = "setup failed: " + e;
            return cr;
        }

        result.decoderName = renderer.getActiveDecoderName();
        cr.keys = renderer.getDecoderDiagnostics().get("low_latency_keys");

        boolean started = false;
        try {
            renderer.start();
            started = true;
            int[] cursor = {0, 1};
            feed(renderer, clip, cursor, WARMUP_FRAMES, true);
            waitForDrain(renderer.getPipelineStats());
            renderer.getPipelineStats().resetStatistics(System.nanoTime());

            cr.idrRequests = feed(renderer, clip, cursor, MEASURE_FRAMES, true);
            waitForDrain(renderer.getPipelineStats());
            PipelineStats.Snapshot s = renderer.getPipelineStats().getSessionSnapshot(System.nanoTime());

            PipelineStats.StageSummary decode = s.stages[PipelineStats.STAGE_DECODE];
            cr.decodeMeanMs = decode.meanMs;
            cr.decodeP50Ms = decode.p50Ms;
            cr.decodeP95Ms = decode.p95Ms;
            cr.decodeMaxMs = decode.maxMs;
            cr.roundP95Ms.add(decode.p95Ms);
            cr.submitMeanMs = s.stages[PipelineStats.STAGE_SUBMIT].meanMs;
            cr.decodedPercent = s.counters.framesQueued > 0 ?
                    100.0 * s.counters.framesDecoded / s.counters.framesQueued : 0;
            if (renderer.isDisplayTimeReported() && s.counters.framesDisplayed > 0) {
                cr.displayedFps = s.fps(s.counters.framesDisplayed);
                cr.displayMeanMs = s.stages[PipelineStats.STAGE_DISPLAY].meanMs;
            }
            cr.rounds = 1;

            double expectedDisplayFps = Math.min(FPS, displayRefreshRate > 0 ? displayRefreshRate : FPS);
            if (rendererFailure != null) {
                cr.valid = false;
                cr.error = rendererFailure;
            }
            else if (decode.count == 0 || cr.decodedPercent < 95) {
                cr.valid = false;
                cr.error = String.format(Locale.ROOT, "only %.0f%% of frames decoded", cr.decodedPercent);
            }
            else if (cr.idrRequests > 0) {
                cr.valid = false;
                cr.error = cr.idrRequests + " decoder errors (IDR requested)";
            }
            else if (cr.displayedFps >= 0 && cr.displayedFps < 0.8 * expectedDisplayFps) {
                cr.valid = false;
                cr.error = String.format(Locale.ROOT, "only %.1f FPS displayed", cr.displayedFps);
            }
        } catch (RuntimeException e) {
            cr.valid = false;
            cr.error = String.valueOf(e);
        } finally {
            stopRenderer(renderer, started);
        }
        return cr;
    }

    private double measureThroughput(AnnexBClip clip, int mode, String glRenderer) {
        rendererFailure = null;
        // Frames are decoded but not rendered: otherwise the result is capped by the display
        // (a 60 Hz surface tops out around 2x its refresh rate), not by the decoder
        MediaCodecDecoderRenderer renderer = createRenderer(mode, glRenderer, true);
        try {
            if (renderer.setup(hevc ? MoonBridge.VIDEO_FORMAT_H265 : MoonBridge.VIDEO_FORMAT_H264, WIDTH, HEIGHT, FPS) != 0) {
                return -1;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Throughput test setup failed: " + e);
            return -1;
        }
        boolean started = false;
        try {
            renderer.start();
            started = true;
            int[] cursor = {0, 1};
            feed(renderer, clip, cursor, WARMUP_FRAMES, true);
            waitForDrain(renderer.getPipelineStats());
            long start = System.nanoTime();
            renderer.getPipelineStats().resetStatistics(start);
            feed(renderer, clip, cursor, THROUGHPUT_FRAMES, false);
            long lastOutput = waitForDrain(renderer.getPipelineStats());
            long decoded = renderer.getPipelineStats().getTotalFramesDecoded();
            double seconds = (lastOutput - start) / 1_000_000_000.0;
            return seconds > 0 && rendererFailure == null ? decoded / seconds : -1;
        } catch (RuntimeException e) {
            LimeLog.warning("Throughput test failed: " + e);
            return -1;
        } finally {
            stopRenderer(renderer, started);
        }
    }

    private static void merge(ConfigResult into, ConfigResult r) {
        int n = into.rounds;
        into.decodeMeanMs = (into.decodeMeanMs * n + r.decodeMeanMs) / (n + 1);
        into.decodeP50Ms = (into.decodeP50Ms * n + r.decodeP50Ms) / (n + 1);
        into.decodeP95Ms = (into.decodeP95Ms * n + r.decodeP95Ms) / (n + 1);
        into.decodeMaxMs = Math.max(into.decodeMaxMs, r.decodeMaxMs);
        into.submitMeanMs = (into.submitMeanMs * n + r.submitMeanMs) / (n + 1);
        into.decodedPercent = Math.min(into.decodedPercent, r.decodedPercent);
        if (r.displayedFps >= 0) {
            into.displayedFps = into.displayedFps >= 0 ? Math.min(into.displayedFps, r.displayedFps) : r.displayedFps;
            into.displayMeanMs = into.displayMeanMs >= 0 ? (into.displayMeanMs + r.displayMeanMs) / 2 : r.displayMeanMs;
        }
        into.idrRequests += r.idrRequests;
        into.roundP95Ms.addAll(r.roundP95Ms);
        into.valid &= r.valid;
        if (into.error == null) {
            into.error = r.error;
        }
        into.rounds++;
    }

    /** Runs the whole benchmark. Blocking: call from a background thread. */
    public Result run(Listener listener) {
        Result result = new Result();
        result.mime = hevc ? "video/hevc" : "video/avc";
        result.width = WIDTH;
        result.height = HEIGHT;
        result.fps = FPS;

        GlPreferences glPrefs = GlPreferences.readPreferences(activity);
        MediaCodecHelper.initialize(activity, glPrefs.glRenderer);

        AnnexBClip clip;
        try (InputStream in = activity.getAssets().open(hevc ? "bench/hevc_1080p60.h265" : "bench/h264_1080p60.h264")) {
            clip = AnnexBClip.load(in, hevc);
        } catch (IOException e) {
            result.report = "Unable to load benchmark clip: " + e;
            listener.onFinished(result);
            return result;
        }

        // A codec that fails hard on one configuration must not take the whole app down
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (t.getName().startsWith("Video - ")) {
                    LimeLog.warning("Benchmark: " + t.getName() + " died: " + e);
                    rendererFailure = String.valueOf(e);
                }
                else if (previousHandler != null) {
                    previousHandler.uncaughtException(t, e);
                }
            }
        });

        try {
            ConfigResult[] merged = new ConfigResult[MODES.length];
            int step = 0;
            int totalSteps = MODES.length * ROUNDS + 2;

            // Discarded warm-up run. In the first ZTE run the configuration measured first
            // (always "original") was ~0.9 ms slower than in later runs: JIT, CPU and decoder
            // clocks were still cold.
            step++;
            listener.onProgress(String.format(Locale.ROOT, "[%d/%d] %s: warm-up",
                    step, totalSteps, hevc ? "HEVC" : "H.264"));
            runConfig(clip, MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL, glPrefs.glRenderer, result);

            for (int round = 0; round < ROUNDS && !cancelled; round++) {
                for (int j = 0; j < MODES.length && !cancelled; j++) {
                    // Alternate the order so no configuration always runs first or last
                    int i = (round % 2 == 0) ? j : MODES.length - 1 - j;
                    step++;
                    listener.onProgress(String.format(Locale.ROOT, "[%d/%d] %s: %s",
                            step, totalSteps, hevc ? "HEVC" : "H.264",
                            MediaCodecHelper.lowLatencyModeName(MODES[i])));
                    ConfigResult r = runConfig(clip, MODES[i], glPrefs.glRenderer, result);
                    if (merged[i] == null) {
                        merged[i] = r;
                    }
                    else {
                        merge(merged[i], r);
                    }
                }
            }
            if (cancelled) {
                return result;
            }
            for (ConfigResult r : merged) {
                result.configs.add(r);
            }

            chooseRecommendation(result);

            listener.onProgress(String.format(Locale.ROOT, "[%d/%d] throughput", totalSteps, totalSteps));
            int throughputMode = result.recommended != null ? result.recommended.mode : MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL;
            result.maxThroughputFps = measureThroughput(clip, throughputMode, glPrefs.glRenderer);

            if (result.recommended != null) {
                DecoderTuningStore.save(activity, result.decoderName, result.mime, result.recommended.mode,
                        String.format(Locale.ROOT, "%s (decode p95 %.2f ms, %s)",
                                MediaCodecHelper.lowLatencyModeName(result.recommended.mode),
                                result.recommended.decodeP95Ms, result.recommendationReason));
            }

            result.report = buildReport(result);
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                File f = new File(SessionReport.getDirectory(activity),
                        "decoder-bench-" + ts + "-" + (hevc ? "hevc" : "h264") + ".txt");
                SessionReport.writeString(f, result.report);
                result.reportFile = f;
            } catch (IOException e) {
                LimeLog.warning("Unable to save benchmark report: " + e);
            }
            listener.onFinished(result);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
        return result;
    }

    private static ConfigResult findMode(Result result, int mode) {
        for (ConfigResult r : result.configs) {
            if (r.mode == mode) {
                return r;
            }
        }
        return null;
    }

    /**
     * Picks the valid configuration with the lowest p95 decode latency. A configuration other
     * than Moonlight's original one is only recommended if it is clearly better (by at least
     * 0.5 ms and 5 % at p95 on average) AND faster than the original in every single round;
     * otherwise there is no reproducible reason to deviate from it.
     */
    static void chooseRecommendation(Result result) {
        ConfigResult best = null;
        for (ConfigResult r : result.configs) {
            if (r.valid && (best == null || r.decodeP95Ms < best.decodeP95Ms)) {
                best = r;
            }
        }
        ConfigResult original = findMode(result, MediaCodecHelper.LOW_LATENCY_MODE_ORIGINAL);
        if (best == null) {
            result.recommended = null;
            result.recommendationReason = "no valid configuration";
            return;
        }
        if (original != null && original.valid && best != original) {
            double gain = original.decodeP95Ms - best.decodeP95Ms;
            if (gain < 0.5 || gain < 0.05 * original.decodeP95Ms) {
                result.recommended = original;
                result.recommendationReason = String.format(Locale.ROOT,
                        "no significant gain over original (%.2f ms)", gain);
                return;
            }
            if (!fasterInEveryRound(best, original)) {
                result.recommended = original;
                // NB: no MediaCodecHelper calls here, this method is unit tested on the JVM
                result.recommendationReason = String.format(Locale.ROOT,
                        "a configuration was %.2f ms faster on average but not in every round", gain);
                return;
            }
            result.recommended = best;
            result.recommendationReason = String.format(Locale.ROOT, "%.2f ms faster than original at p95", gain);
            return;
        }
        result.recommended = best;
        result.recommendationReason = best == original ? "original is fastest" : "original configuration failed";
    }

    private static boolean fasterInEveryRound(ConfigResult candidate, ConfigResult original) {
        if (candidate.roundP95Ms.isEmpty() || candidate.roundP95Ms.size() != original.roundP95Ms.size()) {
            // No per-round data to check
            return true;
        }
        for (int i = 0; i < candidate.roundP95Ms.size(); i++) {
            if (candidate.roundP95Ms.get(i) >= original.roundP95Ms.get(i)) {
                return false;
            }
        }
        return true;
    }

    private String buildReport(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Decoder benchmark: %s %s %dx%d@%d, %d+%d frames x %d rounds " +
                        "(alternating order, after a discarded warm-up run)%n",
                result.decoderName, hevc ? "HEVC" : "H.264", result.width, result.height, result.fps,
                WARMUP_FRAMES, MEASURE_FRAMES, ROUNDS));
        sb.append(String.format(Locale.ROOT, "Display refresh: %.2f Hz%n%n", displayRefreshRate));
        sb.append(String.format(Locale.ROOT, "%-9s %-7s %-7s %-7s %-7s %-6s %-7s %-7s %-16s %s%n",
                "mode", "mean", "p50", "p95", "max", "out%", "dispFPS", "dispMs", "p95 per round", "status / keys"));
        for (ConfigResult r : result.configs) {
            StringBuilder rounds = new StringBuilder();
            for (Double p95 : r.roundP95Ms) {
                if (rounds.length() > 0) {
                    rounds.append('/');
                }
                rounds.append(String.format(Locale.ROOT, "%.2f", p95));
            }
            sb.append(String.format(Locale.ROOT, "%-9s %-7.2f %-7.2f %-7.2f %-7.2f %-6.1f %-7s %-7s %-16s %s%n",
                    MediaCodecHelper.lowLatencyModeName(r.mode),
                    r.decodeMeanMs, r.decodeP50Ms, r.decodeP95Ms, r.decodeMaxMs, r.decodedPercent,
                    r.displayedFps >= 0 ? String.format(Locale.ROOT, "%.1f", r.displayedFps) : "n/a",
                    r.displayMeanMs >= 0 ? String.format(Locale.ROOT, "%.2f", r.displayMeanMs) : "n/a",
                    rounds,
                    (r.valid ? "OK" : "FAIL: " + r.error) + " | " + r.keys));
        }
        sb.append('\n');
        if (result.recommended != null) {
            sb.append("Recommended (used by 'auto'): ")
                    .append(MediaCodecHelper.lowLatencyModeName(result.recommended.mode))
                    .append(" - ").append(result.recommendationReason).append('\n');
        }
        else {
            sb.append("No recommendation: ").append(result.recommendationReason).append('\n');
        }
        if (result.maxThroughputFps > 0) {
            sb.append(String.format(Locale.ROOT, "Max decode throughput: %.0f FPS at %dx%d -> 60 FPS %s, 120 FPS %s%n",
                    result.maxThroughputFps, result.width, result.height,
                    result.maxThroughputFps >= 72 ? "OK" : "NOT sustainable",
                    result.maxThroughputFps >= 144 ? "OK" : "NOT sustainable"));
        }
        sb.append("\nDecode = queueInputBuffer() -> dequeueOutputBuffer(); measured with System.nanoTime().\n")
                .append("dispMs = release -> display time reported by Android (OnFrameRenderedListener), if supported.\n")
                .append("dispMs depends on the phase between the feed timer and vsync (it can differ by up to one\n")
                .append("refresh between runs), so it is NOT used to compare configurations.\n")
                .append("Max throughput is measured without rendering (decoder capacity only).\n")
                .append("Network, host encoding and TV processing are NOT included. Verify visually that the\n")
                .append("frame counter in the top-left corner moved smoothly during every run.\n");
        return sb.toString();
    }
}
