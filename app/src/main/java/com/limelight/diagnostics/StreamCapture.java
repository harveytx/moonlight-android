package com.limelight.diagnostics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;

import com.limelight.LimeLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ULL: saves the decoded video frame as a PNG at a fixed interval, in the report folder of the
 * app version, so the stream can be compared pixel by pixel with screenshots taken on the PC.
 *
 * PixelCopy reads the last buffer the decoder queued to the stream's SurfaceView, converts it to
 * RGB on the GPU and copies it 1:1 into a bitmap of the stream's size. That is the decoder's
 * output: it does not include the performance overlay, the box's own video processing or the
 * HDMI output. Devices that put video on a hardware plane the GPU cannot read return an error or
 * a black image; both are reported. The copy holds the last frame for a few milliseconds, so
 * leave captures off when measuring latency. The PNG is encoded on a background-priority thread.
 */
public final class StreamCapture {
    /** Codec/bitrate part of the file name, read when each capture is taken. */
    public interface LabelProvider {
        String getCaptureLabel();
    }

    /** Result of the first capture of the session, delivered on the main thread. */
    public interface FirstResultListener {
        void onFirstCapture(int status, String detail);
    }

    public static final int STATUS_OK = 0;
    public static final int STATUS_BLACK = 1;
    public static final int STATUS_FAILED = 2;

    // First capture early, so a device that cannot capture is noticed at the start of a test
    private static final long FIRST_CAPTURE_DELAY_MS = 5000;
    // Brightest channel value still considered black (limited range shown as full gives ~16)
    private static final int BLACK_THRESHOLD = 24;
    private static final int SAMPLE_COLUMNS = 32;
    private static final int SAMPLE_ROWS = 18;

    private final Activity activity;
    private final SurfaceView view;
    private final int width, height;
    private final long intervalMs;
    private final LabelProvider labelProvider;
    private final FirstResultListener firstResultListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> results = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private HandlerThread worker;
    private Handler workerHandler;
    private Bitmap bitmap;
    private volatile boolean running;
    private boolean firstReported;

    public StreamCapture(Activity activity, SurfaceView view, int width, int height, int intervalSec,
                         LabelProvider labelProvider, FirstResultListener firstResultListener) {
        this.activity = activity;
        this.view = view;
        this.width = width;
        this.height = height;
        this.intervalMs = intervalSec * 1000L;
        this.labelProvider = labelProvider;
        this.firstResultListener = firstResultListener;
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public void start() {
        if (running || !isSupported() || intervalMs <= 0) {
            return;
        }
        running = true;
        worker = new HandlerThread("ULL capture", Process.THREAD_PRIORITY_BACKGROUND);
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        mainHandler.postDelayed(tick, FIRST_CAPTURE_DELAY_MS);
    }

    /** Stops capturing; a PNG that is being written is finished. */
    public void stop() {
        running = false;
        mainHandler.removeCallbacks(tick);
        if (worker != null) {
            worker.quitSafely();
        }
    }

    /** One line per capture attempt: file name and result. */
    public List<String> getResults() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            mainHandler.postDelayed(this, intervalMs);
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    capture();
                }
            });
        }
    };

    static String fileName(Date when, String label) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(when);
        String safeLabel = label == null ? "" : label.replaceAll("[^A-Za-z0-9._-]", "_");
        return "capture-" + ts + (safeLabel.isEmpty() ? "" : "-" + safeLabel) + ".png";
    }

    /** True if every sampled pixel (ARGB) has all channels at or below the threshold. */
    static boolean isBlack(int[] argbSamples, int threshold) {
        for (int p : argbSamples) {
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            if (r > threshold || g > threshold || b > threshold) {
                return false;
            }
        }
        return argbSamples.length > 0;
    }

    private static String pixelCopyError(int result) {
        switch (result) {
            case PixelCopy.ERROR_TIMEOUT: return "timeout";
            case PixelCopy.ERROR_SOURCE_NO_DATA: return "no frame in the surface yet";
            case PixelCopy.ERROR_SOURCE_INVALID: return "surface cannot be read";
            case PixelCopy.ERROR_DESTINATION_INVALID: return "invalid bitmap";
            default: return "error " + result;
        }
    }

    // Worker thread
    @TargetApi(Build.VERSION_CODES.N)
    private void capture() {
        if (!running) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            addResult("skipped: the previous capture is still being saved");
            return;
        }
        final String name = fileName(new Date(), labelProvider != null ? labelProvider.getCaptureLabel() : null);
        try {
            Surface surface = view.getHolder().getSurface();
            if (surface == null || !surface.isValid()) {
                finish(name, STATUS_FAILED, "surface not available");
                return;
            }
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            PixelCopy.request(view, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int copyResult) {
                    onCopied(name, copyResult);
                }
            }, workerHandler);
        } catch (RuntimeException e) {
            finish(name, STATUS_FAILED, e.toString());
        }
    }

    // Worker thread
    private void onCopied(String name, int copyResult) {
        if (copyResult != PixelCopy.SUCCESS) {
            finish(name, STATUS_FAILED, "PixelCopy: " + pixelCopyError(copyResult));
            return;
        }

        int[] samples = new int[SAMPLE_COLUMNS * SAMPLE_ROWS];
        for (int row = 0; row < SAMPLE_ROWS; row++) {
            for (int col = 0; col < SAMPLE_COLUMNS; col++) {
                int x = (int) ((col + 0.5) * width / SAMPLE_COLUMNS);
                int y = (int) ((row + 0.5) * height / SAMPLE_ROWS);
                samples[row * SAMPLE_COLUMNS + col] = bitmap.getPixel(x, y);
            }
        }
        boolean black = isBlack(samples, BLACK_THRESHOLD);

        long startMs = SystemClock.elapsedRealtime();
        File out = new File(SessionReport.getDirectory(activity), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG encoding failed");
            }
        } catch (IOException e) {
            finish(name, STATUS_FAILED, e.toString());
            return;
        }
        long pngMs = SystemClock.elapsedRealtime() - startMs;
        finish(name, black ? STATUS_BLACK : STATUS_OK, String.format(Locale.ROOT, "%dx%d, PNG %d ms%s",
                width, height, pngMs, black ? ", all black: the video may be on a plane apps cannot read" : ""));
    }

    private void finish(final String name, final int status, final String detail) {
        addResult(name + ": " + (status == STATUS_OK ? "ok" : status == STATUS_BLACK ? "black" : "failed") + " (" + detail + ")");
        LimeLog.info("ULL capture " + name + ": " + detail);
        busy.set(false);
        if (firstResultListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!firstReported) {
                        firstReported = true;
                        firstResultListener.onFirstCapture(status, status == STATUS_FAILED ? detail : name);
                    }
                }
            });
        }
    }

    private void addResult(String line) {
        synchronized (results) {
            results.add(line);
        }
    }
}
