package com.apolito.codexusage.preview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

/** Separate diagnostic APK; never connects an account or represents real usage. */
public final class NativeProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView result = new TextView(this);
        result.setTextSize(16);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        result.setPadding(padding, padding, padding, padding);
        result.setText("Offline native check\n\nSynthetic data only.\n\nLoading account components…");
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        Button storage = new Button(this);
        storage.setText("Synthetic storage checks");
        storage.setOnClickListener(view -> startActivity(new Intent(this, NativeStorageActivity.class)));
        column.addView(storage);
        column.addView(result);
        scroll.addView(column);
        setContentView(scroll);

        new Thread(() -> {
            String message;
            try {
                JSONObject report = new JSONObject(NativeProbe.run());
                boolean passed = !report.has("error")
                        && "offline-synthetic-only".equals(report.optString("mode"))
                        && report.has("device_code_request_denied")
                        && report.getString("device_code_request_denied")
                                .contains("application network policy is unavailable")
                        && report.has("usage_request_denied")
                        && report.getString("usage_request_denied")
                                .contains("application network policy is unavailable")
                        && report.optLong("synthetic_expiration") == 1700000000L
                        && report.has("credential_store_opened")
                        && !report.getBoolean("credential_store_opened")
                        && report.has("transport_attempted")
                        && !report.getBoolean("transport_attempted");
                message = (passed ? "OFFLINE CHECK PASSED" : "OFFLINE CHECK FAILED")
                        + "\n\nSynthetic data only. This does not establish live sign-in.\n\n"
                        + report.toString(2);
            } catch (Exception | LinkageError error) {
                message = "OFFLINE CHECK FAILED\n\n" + error.getClass().getSimpleName()
                        + ": " + error.getMessage();
            }
            String display = message;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) result.setText(display);
            });
        }, "native-offline-probe").start();
    }
}
