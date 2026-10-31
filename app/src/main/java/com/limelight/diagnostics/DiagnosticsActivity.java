package com.limelight.diagnostics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.DecoderTuningStore;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.preferences.GlPreferences;
import com.limelight.utils.UiHelper;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ULL diagnostics and benchmarking hub: device capability report, on-device decoder
 * benchmarks, recorded streaming sessions and A/B comparison between sessions.
 */
public class DiagnosticsActivity extends Activity {
    private TextView output;
    private ScrollView outputScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_ull_diagnostics);
        UiHelper.notifyNewRootView(this);

        output = findViewById(R.id.ull_output);
        outputScroll = findViewById(R.id.ull_output_scroll);
        output.setText(getString(R.string.ull_diag_intro, SessionReport.getDirectory(this).getAbsolutePath()));

        findViewById(R.id.ull_btn_capabilities).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCapabilityReport();
            }
        });
        findViewById(R.id.ull_btn_bench_h264).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBenchmark(false);
            }
        });
        Button hevcButton = findViewById(R.id.ull_btn_bench_hevc);
        hevcButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBenchmark(true);
            }
        });
        findViewById(R.id.ull_btn_sessions).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSessions();
            }
        });
        findViewById(R.id.ull_btn_compare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                compareSessions();
            }
        });
        findViewById(R.id.ull_btn_clear_tuning).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DecoderTuningStore.clear(DiagnosticsActivity.this);
                setOutput(getString(R.string.ull_diag_tuning_cleared));
            }
        });

        // Only offer the HEVC benchmark if there is a hardware HEVC decoder
        MediaCodecHelper.initialize(this, GlPreferences.readPreferences(this).glRenderer);
        if (MediaCodecHelper.getHardwareDecodersForMime("video/hevc").isEmpty()) {
            hevcButton.setVisibility(View.GONE);
        }

        findViewById(R.id.ull_btn_capabilities).requestFocus();
    }

    private void setOutput(String text) {
        output.setText(text);
        outputScroll.scrollTo(0, 0);
    }

    private void runCapabilityReport() {
        setOutput(getString(R.string.ull_diag_working));
        // Querying vendor parameters instantiates codecs, so keep it off the UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = DeviceCapabilityReport.build(DiagnosticsActivity.this);
                    String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                    File f = new File(SessionReport.getDirectory(DiagnosticsActivity.this), "capabilities-" + ts + ".txt");
                    SessionReport.writeString(f, report);
                    report = getString(R.string.ull_diag_saved_to, f.getAbsolutePath()) + "\n\n" + report;
                    LimeLog.info(report);
                } catch (Exception e) {
                    report = "Capability report failed: " + e;
                }
                final String text = report;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setOutput(text);
                    }
                });
            }
        }).start();
    }

    private void startBenchmark(boolean hevc) {
        Intent intent = new Intent(this, DecoderBenchmarkActivity.class);
        intent.putExtra(DecoderBenchmarkActivity.EXTRA_HEVC, hevc);
        startActivity(intent);
    }

    private String[] describeSessions(List<File> sessions) {
        String[] items = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            try {
                items[i] = SessionReport.describe(SessionReport.load(sessions.get(i)));
            } catch (Exception e) {
                items[i] = sessions.get(i).getName();
            }
        }
        return items;
    }

    private void showSessions() {
        final List<File> sessions = SessionReport.list(this);
        if (sessions.isEmpty()) {
            setOutput(getString(R.string.ull_diag_no_sessions));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ull_diag_sessions)
                .setItems(describeSessions(sessions), (dialog, which) -> {
                    try {
                        JSONObject report = SessionReport.load(sessions.get(which));
                        setOutput(sessions.get(which).getName() + "\n" +
                                SessionReport.toReadableText(this, report));
                    } catch (Exception e) {
                        setOutput("Unable to read session: " + e);
                    }
                })
                .show();
    }

    private void compareSessions() {
        final List<File> sessions = SessionReport.list(this);
        if (sessions.size() < 2) {
            setOutput(getString(R.string.ull_diag_need_two_sessions));
            return;
        }
        final String[] items = describeSessions(sessions);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ull_diag_pick_a)
                .setItems(items, (dialogA, a) -> new AlertDialog.Builder(this)
                        .setTitle(R.string.ull_diag_pick_b)
                        .setItems(items, (dialogB, b) -> {
                            try {
                                setOutput(SessionReport.compare(this,
                                        SessionReport.load(sessions.get(a)),
                                        SessionReport.load(sessions.get(b))));
                            } catch (Exception e) {
                                setOutput("Unable to compare sessions: " + e);
                            }
                        })
                        .show())
                .show();
    }
}
