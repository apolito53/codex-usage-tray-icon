package com.apolito.codexusage.preview;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.apolito.codexusage.preview.storage.StorageFixtureDiagnostics;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Internal diagnostic screen. Every value is a fixed synthetic fixture. */
public final class NativeStorageActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Button> actions = new ArrayList<>();
    private TextView result;
    private volatile boolean storageInitialized;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Synthetic storage check\n\nSave, restart the app, then Read to check persistence. No account is connected.");
        title.setTextSize(18);
        column.addView(title);
        addAction(column, "Save fixture", "save");
        addAction(column, "Read fixture", "read");
        addAction(column, "Overwrite fixture", "overwrite");
        addAction(column, "Delete fixture", "delete");
        Button faults = new Button(this);
        faults.setText("Run vault failure checks");
        faults.setEnabled(false);
        faults.setOnClickListener(view -> {
            actions.forEach(action -> action.setEnabled(false));
            result.setText("Checking synthetic vault failures…");
            worker.execute(() -> {
                try {
                    JSONObject report = new JSONObject(StorageFixtureDiagnostics.runAll(getApplicationContext()));
                    String[] checks = {"passed", "roundtrip", "key_non_exportable", "reopened_vault",
                            "failed_write_preserves_previous", "unavailable_is_error",
                            "tamper_is_error", "missing_key_is_error"};
                    boolean passed = !report.has("error") && !report.optBoolean("cleanup_failed");
                    for (String check : checks) passed &= report.optBoolean(check);
                    String display = (passed ? "VAULT FAILURE CHECKS PASSED" : "VAULT FAILURE CHECKS FAILED")
                            + "\n\n" + report.toString(2);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        result.setText(display);
                        actions.forEach(action -> action.setEnabled(true));
                    });
                } catch (Exception | LinkageError failure) {
                    showFailure(failure);
                }
            });
        });
        actions.add(faults);
        column.addView(faults);
        Spinner faultChoice = new Spinner(this);
        String[] faultCommands = {"fault-unavailable-on", "fault-unavailable-off",
                "fault-fail-next-write", "fault-tamper-record", "fault-remove-key", "fault-clear"};
        faultChoice.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, faultCommands));
        column.addView(faultChoice);
        Button applyFault = new Button(this);
        applyFault.setText("Apply native failure fixture");
        applyFault.setEnabled(false);
        applyFault.setOnClickListener(view -> runCommand((String) faultChoice.getSelectedItem()));
        actions.add(applyFault);
        column.addView(applyFault);
        result = new TextView(this);
        result.setTextSize(14);
        result.setText("Initializing Android Keystore adapter…");
        column.addView(result);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);

        worker.execute(() -> {
            try {
                showReport(NativeStorageProbe.initializeFixture(getApplicationContext()), "initialize");
            } catch (Exception | LinkageError failure) {
                showFailure(failure);
            }
        });
    }

    private void addAction(LinearLayout column, String label, String command) {
        Button button = new Button(this);
        button.setText(label);
        button.setEnabled(false);
        button.setOnClickListener(view -> runCommand(command));
        actions.add(button);
        column.addView(button);
    }

    private void runCommand(String command) {
        actions.forEach(action -> action.setEnabled(false));
        result.setText("Running synthetic " + command + "…");
        worker.execute(() -> {
            try {
                showReport(NativeStorageProbe.command(command), command);
            } catch (Exception | LinkageError failure) {
                showFailure(failure);
            }
        });
    }

    private void showReport(String json, String expectedOperation) throws Exception {
        JSONObject report = new JSONObject(json);
        boolean valid = "synthetic-storage-only".equals(report.optString("mode"))
                && expectedOperation.equals(report.optString("operation"))
                && report.has("ok")
                && report.has("transport_attempted")
                && !report.getBoolean("transport_attempted");
        boolean failed = !valid || !report.optBoolean("ok") || report.has("error");
        if (!failed && "initialize".equals(expectedOperation)) {
            failed = !report.optBoolean("initialized")
                    || !"android-keystore-keyring".equals(report.optString("backend"))
                    || !"keyring-direct".equals(report.optString("storage_mode"));
            storageInitialized = !failed;
        } else if (!failed && expectedOperation.startsWith("fault-")) {
            failed = !report.optBoolean("fault_applied")
                    || !report.has("auth_file_present") || report.getBoolean("auth_file_present");
        } else if (!failed) {
            String state = report.optString("state");
            failed = !report.optBoolean("matches_expected")
                    || !report.has("auth_file_present") || report.getBoolean("auth_file_present")
                    || !("initial".equals(state) || "overwritten".equals(state) || "absent".equals(state));
        }
        String display = (failed ? "STORAGE CHECK FAILED" : "STORAGE COMMAND COMPLETE")
                + "\n\n" + report.toString(2);
        // Fault injection deliberately produces errors; keep recovery controls available.
        boolean enableActions = storageInitialized && valid;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            result.setText(display);
            actions.forEach(action -> action.setEnabled(enableActions));
        });
    }

    private void showFailure(Throwable failure) {
        // Native/adapter errors are sanitized. Do not display arbitrary exception text.
        String display = "STORAGE CHECK FAILED\n\n" + failure.getClass().getSimpleName();
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) result.setText(display);
        });
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
