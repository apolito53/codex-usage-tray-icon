package com.apolito.codexusage.preview

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real native libraries and Android Keystore, with all HTTP answered in process. */
@RunWith(AndroidJUnit4::class)
class NativeAccountLifecycleTest {
    private lateinit var app: Context
    private lateinit var platform: FixturePlatform
    private lateinit var record: File
    private lateinit var home: File
    private var ownsEmptyVault = false
    private var nativeInitialized = false

    @Before
    fun requireCleanDisposableEmulator() {
        // The production vault uses a fixed app-scoped key. Never clear an existing
        // account, and never run this destructive fixture on Anthony's phone.
        assertTrue(
            "Run only on a disposable x86_64 Android emulator",
            Build.FINGERPRINT.contains("test-keys") &&
                Build.DEVICE.startsWith("generic") &&
                Build.HARDWARE in setOf("ranchu", "goldfish") &&
                Build.SUPPORTED_ABIS.contains("x86_64"),
        )
        assertTrue("Instrumentation must not run account work on the main looper", Looper.myLooper() != Looper.getMainLooper())
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val accountDirectory = File(app.noBackupFilesDir, "account-v1")
        record = File(accountDirectory, "account.bin")
        val pending = File(accountDirectory, "account.pending")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("The account directory must not be a link", Files.isSymbolicLink(accountDirectory.toPath()))
        assertFalse("A saved account exists; use a clean emulator", Files.exists(record.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("A pending account record exists; use a clean emulator", Files.exists(pending.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("An account key exists; use a clean emulator", store.containsAlias(KEY_ALIAS))
        platform = FixturePlatform(app)
        // A unique home also prevents initialize() from reusing a pre-existing
        // native singleton whose transport might be the real network adapter.
        home = File(app.noBackupFilesDir, "native-account-instrumentation-${System.nanoTime()}")
        assertTrue("Could not create the isolated fixture home", home.isDirectory || home.mkdirs())
        ownsEmptyVault = true
    }

    @After
    fun removeOnlyFixtureAccount() {
        if (!ownsEmptyVault) return
        platform.releasePoll.countDown()
        // Even a failed assertion must not leave the synthetic account behind.
        if (nativeInitialized) runCatching { NativeAccount.signOut() }
        platform.deleteSecret()
        assertFalse("Fixture cleanup must remove its ciphertext", Files.exists(record.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("Fixture cleanup must remove its pending ciphertext", Files.exists(File(record.parentFile, "account.pending").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("Fixture cleanup must remove its key", keyExists())
    }

    @Test
    fun deviceGrantPersistsRotatesAndClearsWhileCancelledGrantStaysSignedOut() {
        val initialized = success("initialize", NativeAccount.initialize(platform, home.absolutePath))
        nativeInitialized = true
        assertFalse(initialized.optBoolean("connected"))

        val first = success("start login", NativeAccount.startLogin())
        assertEquals("FIXTURE-1", first.getString("userCode"))
        assertEquals("https://auth.openai.com/codex/device", first.getString("verificationUrl"))
        val signedIn = success("finish login", NativeAccount.finishLogin(first.getLong("attempt")))
        assertTrue(signedIn.getBoolean("connected"))
        val accountKey = signedIn.getString("accountKey")
        assertTrue("The saved account must expose an opaque cache owner", accountKey.matches(Regex("[0-9a-f]{64}")))
        assertTrue("Successful login must persist an encrypted record", record.isFile)

        val initialEnvelope = record.readBytes()
        assertFalse("The stored envelope must not contain the synthetic refresh token", initialEnvelope.containsUtf8(INITIAL_REFRESH))
        val recovered = AccountPlatform(app).loadSecret()
        assertNotNull("A new platform instance must decrypt the saved record", recovered)
        assertTrue("The saved session must contain the initial synthetic refresh token", recovered!!.containsUtf8(INITIAL_REFRESH))

        val firstReading = success("first usage", NativeAccount.readUsage())
        assertEquals(72, firstReading.getInt("remaining"))
        assertEquals(RESET_AT, firstReading.getLong("resetsAtSeconds"))
        assertEquals(accountKey, firstReading.getString("accountKey"))
        val secondReading = success("401 refresh and retry", NativeAccount.readUsage())
        assertEquals(39, secondReading.getInt("remaining"))
        assertEquals(RESET_AT, secondReading.getLong("resetsAtSeconds"))
        assertEquals(accountKey, secondReading.getString("accountKey"))
        assertEquals("Expected one initial GET, one 401 and one retry", 3, platform.usageRequests.get())
        assertEquals("Exactly one token refresh is allowed", 1, platform.refreshRequests.get())
        val rotatedEnvelope = record.readBytes()
        assertFalse("Refreshing must write a fresh encrypted record", initialEnvelope.contentEquals(rotatedEnvelope))
        assertFalse("The rotated token must not appear in ciphertext", rotatedEnvelope.containsUtf8(ROTATED_REFRESH))
        val rotated = AccountPlatform(app).loadSecret()
        assertNotNull(rotated)
        assertTrue("The rotated refresh token must be persisted", rotated!!.containsUtf8(ROTATED_REFRESH))
        assertFalse("The previous refresh token must be replaced", rotated.containsUtf8(INITIAL_REFRESH))

        val damaged = rotatedEnvelope.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        try {
            record.writeBytes(damaged)
            try {
                AccountPlatform(app).loadSecret()
                fail("Tampered ciphertext must be rejected")
            } catch (_: IOException) {
                // Expected: authenticated decryption fails closed.
            }
        } finally {
            record.writeBytes(rotatedEnvelope)
        }
        assertNotNull("The restored fixture envelope must still decrypt", AccountPlatform(app).loadSecret())

        val signedOut = success("sign out", NativeAccount.signOut())
        assertFalse(signedOut.optBoolean("connected"))
        assertFalse("Sign-out must remove the encrypted account", record.exists())
        assertFalse("Sign-out must remove the installation key", keyExists())
        assertEquals("Sign-out must request revocation once", 1, platform.revokeRequests.get())
        assertTrue("A successful synthetic revoke must be reported", signedOut.getBoolean("revocationConfirmed"))
        assertFalse("No account must remain readable", NativeAccount.readUsage().reply().optBoolean("ok"))

        val second = success("start second login", NativeAccount.startLogin())
        assertEquals("FIXTURE-2", second.getString("userCode"))
        val attempt = second.getLong("attempt")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val finishing = workers.submit(Callable { NativeAccount.finishLogin(attempt) })
            assertTrue("Device-code polling did not start", platform.pollEntered.await(10, TimeUnit.SECONDS))
            val cancelling = workers.submit(Callable { NativeAccount.cancelLogin(attempt) })
            try {
                val cancelled = success("cancel login", cancelling.get(10, TimeUnit.SECONDS))
                assertFalse("A cancelled grant must not be connected", cancelled.optBoolean("connected"))
            } finally {
                // Simulate a successful server response arriving after cancellation.
                platform.releasePoll.countDown()
            }
            assertFalse("A late grant must not complete the cancelled login", finishing.get(10, TimeUnit.SECONDS).reply().optBoolean("ok"))
        } finally {
            platform.releasePoll.countDown()
            workers.shutdownNow()
        }
        assertFalse("Cancelled login must not write an account", record.exists())
        assertFalse("Cancelled login must not create a Keystore key", keyExists())
        assertFalse("Cancelled login must remain signed out", NativeAccount.readUsage().reply().optBoolean("ok"))
        assertTrue("Synthetic transport rejected an unexpected request", platform.failure == null)
        assertEquals(
            "Only the device, passive usage, refresh, and revocation requests are allowed",
            listOf("start", "poll", "exchange", "usage", "usage", "refresh", "usage", "revoke", "start", "poll"),
            platform.eventLog(),
        )
    }

    private fun keyExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS)

    private fun success(stage: String, json: String): JSONObject = json.reply().also {
        assertTrue("$stage failed: ${it.optString("error", "unknown")}", it.optBoolean("ok"))
    }

    private fun String.reply() = JSONObject(this)

    internal class FixturePlatform(
        context: Context,
        private val expectedRevokeToken: String = ROTATED_REFRESH,
    ) : AccountPlatform(context) {
        private val fixtureApp = context.applicationContext
        val usageRequests = AtomicInteger()
        val refreshRequests = AtomicInteger()
        val revokeRequests = AtomicInteger()
        val pollEntered = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        private val starts = AtomicInteger()
        private val polls = AtomicInteger()
        private val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var failure: String? = null
            private set

        override fun request(method: String, url: String, headers: String, body: ByteArray?): String {
            try {
                val headerMap = JSONObject(headers)
                val names = headerMap.keys().asSequence().map { it.lowercase(Locale.ROOT) }.toSet()
                check(names.none { it == "cookie" || it == "cookie2" || ("luna" in it && "reserve" in it) })
                if (url != USAGE_URL) {
                    check(names.none { it == "authorization" || it == "chatgpt-account-id" || it == "x-openai-fedramp" })
                }
                return when (url) {
                    USER_CODE_URL -> {
                        check(method == "POST")
                        check(JSONObject(body.utf8()).optString("client_id").isNotEmpty())
                        val number = starts.incrementAndGet()
                        check(number in 1..2)
                        events += "start"
                        response(200, JSONObject().put("device_auth_id", "fixture-device-$number")
                            .put("user_code", "FIXTURE-$number").put("interval", "0"))
                    }
                    DEVICE_TOKEN_URL -> {
                        check(method == "POST")
                        val number = polls.incrementAndGet()
                        check(number in 1..2)
                        val request = JSONObject(body.utf8())
                        check(request.getString("device_auth_id") == "fixture-device-$number")
                        check(request.getString("user_code") == "FIXTURE-$number")
                        events += "poll"
                        if (number == 2) {
                            pollEntered.countDown()
                            check(releasePoll.await(20, TimeUnit.SECONDS))
                        }
                        response(200, JSONObject().put("authorization_code", "fixture-authorization-code")
                            .put("code_challenge", "fixture-challenge").put("code_verifier", "fixture-verifier"))
                    }
                    OAUTH_URL -> {
                        check(method == "POST")
                        val text = body.utf8()
                        if (text.trimStart().startsWith("{")) {
                            val request = JSONObject(text)
                            check(request.getString("grant_type") == "refresh_token")
                            check(request.getString("refresh_token") == INITIAL_REFRESH)
                            check(refreshRequests.incrementAndGet() == 1)
                            events += "refresh"
                            response(200, tokenResponse(ROTATED_ACCESS, ROTATED_REFRESH))
                        } else {
                            val form = text.split('&').associate { entry ->
                                val parts = entry.split('=', limit = 2)
                                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                            }
                            check(form["grant_type"] == "authorization_code")
                            check(form["code"] == "fixture-authorization-code")
                            check(form["code_verifier"] == "fixture-verifier")
                            events += "exchange"
                            response(200, tokenResponse(INITIAL_ACCESS, INITIAL_REFRESH))
                        }
                    }
                    USAGE_URL -> {
                        check(method == "GET" && (body == null || body.isEmpty()))
                        val number = usageRequests.incrementAndGet()
                        check(number in 1..3)
                        check(header(headerMap, "chatgpt-account-id") == ACCOUNT_ID)
                        val expected = if (number < 3) INITIAL_ACCESS else ROTATED_ACCESS
                        check(header(headerMap, "authorization") == "Bearer $expected")
                        events += "usage"
                        if (number == 2) response(401, JSONObject())
                        else response(200, usagePayload(if (number == 1) 28 else 61))
                    }
                    REVOKE_URL -> {
                        check(method == "POST")
                        val request = JSONObject(body.utf8())
                        check(request.getString("token") == expectedRevokeToken)
                        check(request.getString("token_type_hint") == "refresh_token")
                        check(request.getString("client_id").isNotEmpty())
                        check(!File(fixtureApp.noBackupFilesDir, "account-v1/account.bin").exists())
                        check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS))
                        check(revokeRequests.incrementAndGet() == 1)
                        events += "revoke"
                        response(200, JSONObject())
                    }
                    else -> error("Unexpected synthetic request")
                }
            } catch (_: Exception) {
                failure = "Synthetic request rejected"
                throw IOException("Synthetic request rejected")
            }
        }

        fun eventLog(): List<String> = synchronized(events) { events.toList() }

        private fun header(headers: JSONObject, name: String): String? {
            val actual = headers.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
            val values = headers.getJSONArray(actual)
            return if (values.length() == 1) values.getString(0) else null
        }

        private fun response(status: Int, body: JSONObject) = JSONObject()
            .put("status", status).put("body", body.toString())
            .put("headers", JSONObject().put("content-type", JSONArray().put("application/json"))).toString()

        private fun tokenResponse(access: String, refresh: String) = JSONObject()
            .put("id_token", ID_TOKEN).put("access_token", access).put("refresh_token", refresh)

        private fun usagePayload(weeklyUsed: Int): JSONObject {
            fun window(used: Int, seconds: Int) = JSONObject().put("used_percent", used)
                .put("limit_window_seconds", seconds).put("reset_after_seconds", 60).put("reset_at", RESET_AT)
            return JSONObject().put("plan_type", "pro").put("rate_limit", JSONObject()
                .put("allowed", true).put("limit_reached", false)
                .put("primary_window", window(7, 18_000)).put("secondary_window", window(weeklyUsed, 604_800)))
        }
    }

    companion object {
        internal const val KEY_ALIAS = "com.apolito.codexusage.account.v1"
        private const val USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
        private const val DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
        private const val OAUTH_URL = "https://auth.openai.com/oauth/token"
        private const val REVOKE_URL = "https://auth.openai.com/oauth/revoke"
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val ACCOUNT_ID = "fixture-account"
        internal const val INITIAL_REFRESH = "fixture-refresh-first"
        private const val ROTATED_REFRESH = "fixture-refresh-rotated"
        internal const val RESET_AT = 1_800_000_000L

        private val ID_TOKEN = fixtureJwt("id")
        private val INITIAL_ACCESS = fixtureJwt("access-first")
        private val ROTATED_ACCESS = fixtureJwt("access-rotated")

        private fun fixtureJwt(kind: String): String {
            val claims = JSONObject().put("exp", 2_000_000_000L).put("jti", kind)
                .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", ACCOUNT_ID)
                    .put("chatgpt_user_id", "fixture-user").put("chatgpt_plan_type", "pro"))
            val payload = Base64.encodeToString(claims.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            return "e30.$payload.c2ln"
        }

        private fun ByteArray?.utf8() = requireNotNull(this).toString(Charsets.UTF_8)

        private fun ByteArray.containsUtf8(text: String): Boolean {
            val needle = text.toByteArray(Charsets.UTF_8)
            return indices.any { start ->
                start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
            }
        }
    }
}
