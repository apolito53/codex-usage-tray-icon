package com.apolito.codexusage.preview

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Opt-in two-invocation test; the caller MUST force-stop the app between phases.
 * Ordinary connected tests skip this case and never leave synthetic credentials.
 */
@RunWith(AndroidJUnit4::class)
class NativeAccountProcessRestartTest {
    @Test
    fun syntheticSessionSurvivesAnExplicitProcessDeath() {
        val phase = InstrumentationRegistry.getArguments().getString("accountProcessPhase")
        assumeTrue("Only run during the explicit two-phase restart check", phase != null)
        require(phase == "prepare" || phase == "restore") { "Unknown process-test phase" }
        assertTrue(
            "Run only on a disposable x86_64 Android emulator",
            Build.FINGERPRINT.contains("test-keys") &&
                Build.DEVICE.startsWith("generic") &&
                Build.HARDWARE in setOf("ranchu", "goldfish") &&
                Build.SUPPORTED_ABIS.contains("x86_64"),
        )
        assertTrue("Run account operations off the main looper", Looper.myLooper() != Looper.getMainLooper())
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val accountDir = File(app.noBackupFilesDir, "account-v1")
        val record = File(accountDir, "account.bin")
        val pending = File(accountDir, "account.pending")
        val marker = File(app.noBackupFilesDir, "native-account-restart-fixture.marker")
        val home = File(app.noBackupFilesDir, "native-account-restart-fixture-home")
        assertFalse("Account directory must not be a link", Files.isSymbolicLink(accountDir.toPath()))
        if (phase == "prepare") prepare(app, record, pending, marker, home)
        else restore(app, record, pending, marker, home)
    }

    private fun prepare(app: Context, record: File, pending: File, marker: File, home: File) {
        assertFalse("A saved account exists; use a clean emulator", exists(record))
        assertFalse("A pending account record exists; use a clean emulator", exists(pending))
        assertFalse("An account key exists; use a clean emulator", keyExists())
        assertFalse("A prior fixture marker exists; do not overwrite it", exists(marker))
        assertFalse("A prior fixture home exists; do not overwrite it", exists(home))
        assertTrue(home.mkdirs())
        val platform = NativeAccountLifecycleTest.FixturePlatform(app, NativeAccountLifecycleTest.INITIAL_REFRESH)
        var initialized = false
        var prepared = false
        try {
            val before = success(NativeAccount.initialize(platform, home.absolutePath))
            initialized = true
            assertFalse(before.optBoolean("connected"))
            val challenge = success(NativeAccount.startLogin())
            assertEquals("FIXTURE-1", challenge.getString("userCode"))
            val after = success(NativeAccount.finishLogin(challenge.getLong("attempt")))
            assertTrue(after.getBoolean("connected"))
            val accountKey = after.getString("accountKey")
            assertTrue(accountKey.matches(Regex("[0-9a-f]{64}")))
            assertTrue(Files.isRegularFile(record.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertFalse(exists(pending))
            assertTrue(keyExists())
            assertEquals(listOf("start", "poll", "exchange"), platform.eventLog())
            assertTrue(platform.failure == null)
            val evidence = JSONObject().put("fixture", FIXTURE).put("pid", Process.myPid())
                .put("ciphertextDigest", sha256(record)).put("accountKey", accountKey)
            FileOutputStream(marker).use {
                it.write(evidence.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            // Intentionally retained only for the following restore invocation.
            prepared = true
        } finally {
            if (!prepared) {
                if (initialized) runCatching { NativeAccount.signOut() }
                clearFixture(platform, record, pending, marker, home)
            }
        }
    }

    private fun restore(app: Context, record: File, pending: File, marker: File, home: File) {
        assertTrue("Missing fixture marker", Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue("Fixture marker is too large", marker.length() in 1L..2_048L)
        val evidence = JSONObject(marker.readText(Charsets.UTF_8))
        assertTrue("This marker does not belong to this fixture", evidence.optString("fixture") == FIXTURE)
        assertTrue("Force-stop must start a different app process", evidence.getInt("pid") != Process.myPid())
        assertTrue("Missing fixture home", Files.isDirectory(home.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue("Missing fixture ciphertext", Files.isRegularFile(record.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("Unexpected pending account record", exists(pending))
        assertTrue("Missing fixture key", keyExists())
        assertTrue("Ciphertext no longer matches this fixture", evidence.getString("ciphertextDigest") == sha256(record))
        val platform = NativeAccountLifecycleTest.FixturePlatform(app, NativeAccountLifecycleTest.INITIAL_REFRESH)
        // Confirm the guarded ciphertext actually contains our fixed synthetic
        // token before allowing this test to mutate or delete the vault.
        val stored = requireNotNull(platform.loadSecret())
        val token = JSONObject(stored.toString(Charsets.UTF_8)).getJSONObject("tokens").optString("refresh_token")
        assertTrue("Vault does not contain this fixture", token == NativeAccountLifecycleTest.INITIAL_REFRESH)
        var initialized = false
        try {
            val restored = success(NativeAccount.initialize(platform, home.absolutePath))
            initialized = true
            assertTrue("The session must reload after process death", restored.getBoolean("connected"))
            assertTrue("The restored account identity must match", restored.getString("accountKey") == evidence.getString("accountKey"))
            val usage = success(NativeAccount.readUsage())
            assertEquals(72, usage.getInt("remaining"))
            assertEquals(NativeAccountLifecycleTest.RESET_AT, usage.getLong("resetsAtSeconds"))
            assertTrue(usage.getString("accountKey") == evidence.getString("accountKey"))
            val signedOut = success(NativeAccount.signOut())
            assertEquals(1, platform.revokeRequests.get())
            assertFalse(exists(record))
            assertFalse(keyExists())
            assertEquals(listOf("usage", "revoke"), platform.eventLog())
            assertTrue(platform.failure == null)
            assertTrue("A successful synthetic revoke must be reported", signedOut.getBoolean("revocationConfirmed"))
        } finally {
            if (initialized) runCatching { NativeAccount.signOut() }
            clearFixture(platform, record, pending, marker, home)
        }
    }

    private fun clearFixture(platform: AccountPlatform, record: File, pending: File, marker: File, home: File) {
        platform.deleteSecret()
        assertTrue("Could not remove the fixture marker", !exists(marker) || marker.delete())
        assertTrue("Could not remove the empty fixture home", !exists(home) || home.delete())
        assertFalse("Fixture ciphertext remains", exists(record))
        assertFalse("Pending fixture ciphertext remains", exists(pending))
        assertFalse("Fixture key remains", keyExists())
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun keyExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .containsAlias(NativeAccountLifecycleTest.KEY_ALIAS)
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun success(json: String) = JSONObject(json).also {
        assertTrue("Native account operation failed: ${it.optString("error", "unknown")}", it.optBoolean("ok"))
    }

    companion object {
        private const val FIXTURE = "codex-usage-native-process-restart-v1"
    }
}
