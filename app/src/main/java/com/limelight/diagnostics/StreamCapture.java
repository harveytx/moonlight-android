package com.limelight.diagnostics;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.zip.Deflater;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ULL: saves the decoded video frame as a PNG at a fixed interval, in
 * ull-sessions/<version>/capturas/moonlight/, so the stream can be compared pixel by pixel with
 * the captures of the PC (capturas/pc/, see tools/image-test.html).
 *
 * PixelCopy reads the last buffer the decoder queued to the stream's SurfaceView, converts it to
 * RGB on the GPU and copies it 1:1 into a bitmap of the stream's size. That is the decoder's
 * output: it does not include the performance overlay, the box's own video processing or the
 * HDMI output. Devices that put video on a hardware plane the GPU cannot read return an error or
 * a black image; both are reported.
 *
 * Android's PNG encoder took 0.75-7.5 s per capture on the ZTE and decoding slowed down while it
 * ran (12.2-ULL.7 data). So while streaming, captures are written as uncompressed PNG (little
 * more than a copy), and recompressed after the session with recompressAfterSession(), stopping
 * if another stream starts. Captures still cost some work, so leave them off when measuring
 * latency; the per-frame log marks the frames where a capture was taken.
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
    private final Runnable captureMarker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> results = new ArrayList<>();
    private final List<File> savedFiles = new ArrayList<>();
    // Streams with captures running in this process: recompression waits for none
    private static final AtomicInteger activeCaptures = new AtomicInteger();
    private final AtomicBoolean busy = new AtomicBoolean();
    private HandlerThread worker;
    private Handler workerHandler;
    private Bitmap bitmap;
    private volatile boolean running;
    private boolean firstReported;

    public StreamCapture(Activity activity, SurfaceView view, int width, int height, int intervalSec,
                         LabelProvider labelProvider, FirstResultListener firstResultListener,
                         Runnable captureMarker) {
        this.activity = activity;
        this.view = view;
        this.width = width;
        this.height = height;
        this.intervalMs = intervalSec * 1000L;
        this.labelProvider = labelProvider;
        this.firstResultListener = firstResultListener;
        this.captureMarker = captureMarker;
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public void start() {
        if (running || !isSupported() || intervalMs <= 0) {
            return;
        }
        running = true;
        activeCaptures.incrementAndGet();
        worker = new HandlerThread("ULL capture", Process.THREAD_PRIORITY_BACKGROUND);
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        mainHandler.postDelayed(tick, FIRST_CAPTURE_DELAY_MS);
    }

    /** Stops capturing; a PNG that is being written is finished. */
    public void stop() {
        if (running) {
            activeCaptures.decrementAndGet();
        }
        running = false;
        mainHandler.removeCallbacks(tick);
        if (worker != null) {
            worker.quitSafely();
        }
    }

    /**
     * Rewrites the captures of this session with normal compression (lossless, same pixels).
     * Call after stop(), from a background thread, once the stream is over. Stops early if
     * another stream with captures starts; the remaining files stay valid uncompressed PNGs.
     */
    public void recompressAfterSession() {
        if (worker != null) {
            try {
                worker.join(5000); // the last capture may still be being written
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        List<File> files;
        synchronized (results) {
            files = new ArrayList<>(savedFiles);
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        for (File f : files) {
            if (activeCaptures.get() > 0) {
                LimeLog.info("ULL captures: another stream started, recompression postponed");
                return;
            }
            final Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) {
                continue;
            }
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                final int w = b.getWidth();
                FastPngWriter.write(fos, w, b.getHeight(), new FastPngWriter.RowSource() {
                    @Override
                    public void getRow(int y, int[] argb) {
                        b.getPixels(argb, 0, w, 0, y, w, 1);
                    }
                }, Deflater.DEFAULT_COMPRESSION);
            } catch (IOException | RuntimeException e) {
                LimeLog.warning("ULL capture recompression failed for " + f + ": " + e);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            } finally {
                b.recycle();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
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
            if (captureMarker != null) {
                captureMarker.run();
            }
            final long copyStartMs = SystemClock.elapsedRealtime();
            PixelCopy.request(view, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int copyResult) {
                    onCopied(name, copyResult, SystemClock.elapsedRealtime() - copyStartMs);
                }
            }, workerHandler);
        } catch (RuntimeException e) {
            finish(name, STATUS_FAILED, e.toString());
        }
    }

    // Worker thread
    private void onCopied(String name, int copyResult, long copyMs) {
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
        File out = new File(SessionReport.getCaptureDirectory(activity, SessionReport.CAPTURES_MOONLIGHT), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            // No compression while streaming: little more than a copy (recompressed afterwards)
            FastPngWriter.write(fos, width, height, new FastPngWriter.RowSource() {
                @Override
                public void getRow(int y, int[] argb) {
                    bitmap.getPixels(argb, 0, width, 0, y, width, 1);
                }
            }, Deflater.NO_COMPRESSION);
        } catch (IOException | RuntimeException e) {
            finish(name, STATUS_FAILED, e.toString());
            return;
        }
        synchronized (results) {
            savedFiles.add(out);
        }
        long pngMs = SystemClock.elapsedRealtime() - startMs;
        finish(name, black ? STATUS_BLACK : STATUS_OK, String.format(Locale.ROOT,
                "%dx%d, copy %d ms, PNG %d ms%s", width, height, copyMs, pngMs,
                black ? ", all black: the video may be on a plane apps cannot read" : ""));
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
