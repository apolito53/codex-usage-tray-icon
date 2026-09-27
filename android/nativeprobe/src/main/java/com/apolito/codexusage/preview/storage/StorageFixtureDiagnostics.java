package com.apolito.codexusage.preview.storage;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Explicit destructive controls for this lab's synthetic namespaces only. */
public final class StorageFixtureDiagnostics {
    private StorageFixtureDiagnostics() { }

    public static void failNextWrite(AndroidCredentialVault vault) { vault.fixtureFailNextWrite(); }
    public static void setUnavailable(AndroidCredentialVault vault, boolean unavailable) {
        vault.fixtureSetUnavailable(unavailable);
    }
    public static void tamperOnlyRecord(AndroidCredentialVault vault)
            throws AndroidCredentialVault.VaultException { vault.fixtureTamperOnlyRecord(); }
    public static void removeKey(AndroidCredentialVault vault)
            throws AndroidCredentialVault.VaultException { vault.fixtureRemoveKey(); }
    public static void clearFixture(AndroidCredentialVault vault)
            throws AndroidCredentialVault.VaultException { vault.fixtureClear(); }
    public static boolean hasPlaintextMarker(AndroidCredentialVault vault, byte[] marker)
            throws AndroidCredentialVault.VaultException { return vault.fixtureHasPlaintextMarker(marker); }

    /** Java-side tests only. Separate native tests must prove upstream save/load/logout routing. */
    public static synchronized String runAll(Context context) {
        JSONObject result = new JSONObject();
        AndroidCredentialVault vault = null;
        try {
            vault = new AndroidCredentialVault(context, "synthetic-java-self-check");
            clearFixture(vault);
            final AndroidCredentialVault active = vault;
            String identity = "[null,\"Codex Auth\",\"java-fixture\"]";
            byte[] original = "SYNTHETIC-ONLY-ORIGINAL-SECRET-35791".getBytes(StandardCharsets.UTF_8);
            byte[] replacement = "SYNTHETIC-ONLY-REPLACEMENT-SECRET-24680".getBytes(StandardCharsets.UTF_8);

            require(vault.get(identity) == null, "initial_absence");
            vault.put(identity, original);
            require(Arrays.equals(original, vault.get(identity)), "roundtrip");
            require(!hasPlaintextMarker(vault, original), "ciphertext_only");
            require(vault.fixtureKeyNonExportable(), "key_non_exportable");
            result.put("roundtrip", true);
            result.put("key_non_exportable", true);

            // A new Java object proves persisted storage, but is not process-death evidence.
            AndroidCredentialVault reopened = new AndroidCredentialVault(context, "synthetic-java-self-check");
            require(Arrays.equals(original, reopened.get(identity)), "reopened_vault");
            result.put("reopened_vault", true);

            failNextWrite(vault);
            expectCode(AndroidCredentialVault.WRITE_FAILED, () -> active.put(identity, replacement));
            require(Arrays.equals(original, vault.get(identity)), "failed_write_preserves_previous");
            require(!hasPlaintextMarker(vault, original) && !hasPlaintextMarker(vault, replacement),
                    "failed_write_ciphertext_only");
            result.put("failed_write_preserves_previous", true);

            setUnavailable(vault, true);
            expectCode(AndroidCredentialVault.UNAVAILABLE, () -> active.get(identity));
            setUnavailable(vault, false);
            require(Arrays.equals(original, vault.get(identity)), "unavailable_preserves_previous");
            result.put("unavailable_is_error", true);

            vault.put(identity, replacement);
            require(Arrays.equals(replacement, vault.get(identity)), "overwrite");
            tamperOnlyRecord(vault);
            expectCode(AndroidCredentialVault.CORRUPT, () -> active.get(identity));
            require(vault.delete(identity), "delete_corrupt_record");
            require(vault.get(identity) == null && !vault.delete(identity), "delete_absence");
            result.put("tamper_is_error", true);

            vault.put(identity, original);
            removeKey(vault);
            expectCode(AndroidCredentialVault.KEY_MISSING, () -> active.get(identity));
            expectCode(AndroidCredentialVault.KEY_MISSING, () -> active.put(identity, replacement));
            require(vault.delete(identity) && vault.get(identity) == null, "delete_keyless_record");
            result.put("missing_key_is_error", true);
            result.put("passed", true);
            result.put("process_restart_tested", false);
        } catch (Exception e) {
            try {
                result.put("passed", false);
                if (e instanceof AndroidCredentialVault.VaultException) {
                    result.put("storage_error_code", ((AndroidCredentialVault.VaultException) e).getCode());
                } else {
                    // Check names are fixture constants; no platform exception text is exposed.
                    result.put("error", e instanceof CheckFailure ? e.getMessage() : "fixture_check_failed");
                }
            } catch (Exception ignored) { }
        } finally {
            if (vault != null) {
                try { clearFixture(vault); }
                catch (Exception e) {
                    try { result.put("cleanup_failed", true); result.put("passed", false); }
                    catch (Exception ignored) { }
                }
            }
        }
        return result.toString();
    }

    private interface Check { void run() throws Exception; }
    private static void expectCode(int code, Check check) throws Exception {
        try { check.run(); }
        catch (AndroidCredentialVault.VaultException e) {
            require(e.getCode() == code, "wrong_failure_code");
            return;
        }
        throw new CheckFailure("expected_failure_was_success");
    }
    private static void require(boolean condition, String check) throws CheckFailure {
        if (!condition) throw new CheckFailure(check);
    }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        CheckFailure(String check) { super(check); }
    }
}
