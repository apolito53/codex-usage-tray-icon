package com.apolito.codexusage.preview;

import android.content.Context;
import com.apolito.codexusage.preview.storage.AndroidCredentialVault;
import java.io.File;

/** Fixed synthetic storage commands only; no account connection API. */
final class NativeStorageProbe {
    static { System.loadLibrary("codex_android_native_probe"); }
    private static AndroidCredentialVault vault;
    private static String canonicalHome;
    private NativeStorageProbe() {}
    private static native String initialize(Object vault, String codexHome);
    static native String command(String operation);

    static synchronized String initializeFixture(Context context) throws Exception {
        if (vault == null) {
            Context application = context.getApplicationContext();
            File home = new File(application.getNoBackupFilesDir(),
                    "native-storage-fixtures/synthetic-codex-auth/codex-home");
            if (!home.isDirectory() && !home.mkdirs()) {
                throw new IllegalStateException("Synthetic home unavailable");
            }
            canonicalHome = home.getCanonicalPath();
            vault = new AndroidCredentialVault(application, "synthetic-codex-auth");
        }
        return initialize(vault, canonicalHome);
    }
}
