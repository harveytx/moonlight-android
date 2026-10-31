package com.limelight.diagnostics;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.utils.UiHelper;

/**
 * Full-screen host for {@link DecoderBenchmark}. The benchmark renders to a real SurfaceView
 * so the display path is the same as during streaming.
 */
public class DecoderBenchmarkActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_HEVC = "hevc";

    private DecoderBenchmark benchmark;
    private Thread benchmarkThread;
    private TextView progress;
    private boolean hevc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_ull_decoder_benchmark);

        hevc = getIntent().getBooleanExtra(EXTRA_HEVC, false);
        progress = findViewById(R.id.ull_bench_progress);
        progress.setText(R.string.ull_bench_starting);

        findViewById(R.id.ull_bench_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        SurfaceView surfaceView = findViewById(R.id.ull_bench_surface);
        surfaceView.getHolder().setFixedSize(1920, 1080);
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Ask for a 60 Hz compatible mode, as the streaming activity does for a 60 FPS stream
            holder.getSurface().setFrameRate(60, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (benchmarkThread != null) {
            return;
        }

        float refreshRate = getWindowManager().getDefaultDisplay().getRefreshRate();
        benchmark = new DecoderBenchmark(this, holder, hevc, refreshRate);
        benchmarkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                benchmark.run(new DecoderBenchmark.Listener() {
                    @Override
                    public void onProgress(final String text) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                progress.setText(getString(R.string.ull_bench_running, text));
                            }
                        });
                    }

                    @Override
                    public void onFinished(final DecoderBenchmark.Result result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showResults(result);
                            }
                        });
                    }
                });
            }
        }, "ULL decoder benchmark");
        benchmarkThread.start();
    }

    private void showResults(DecoderBenchmark.Result result) {
        if (isFinishing()) {
            return;
        }
        progress.setVisibility(View.GONE);
        String text = result.report != null ? result.report : "";
        if (result.reportFile != null) {
            text = getString(R.string.ull_diag_saved_to, result.reportFile.getAbsolutePath()) + "\n\n" + text;
        }
        ((TextView) findViewById(R.id.ull_bench_results)).setText(text);
        findViewById(R.id.ull_bench_results_panel).setVisibility(View.VISIBLE);
        findViewById(R.id.ull_bench_close).requestFocus();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopBenchmark();
    }

    private void stopBenchmark() {
        if (benchmark != null) {
            benchmark.cancel();
        }
        if (benchmarkThread != null) {
            try {
                // The renderer must be fully stopped before the surface goes away
                benchmarkThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) {
            stopBenchmark();
        }
    }
}
