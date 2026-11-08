package com.apolito.codexusage.preview

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import javax.crypto.KeyGenerator

/** Policy and ciphertext tests never contact a server or use a saved account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AccountPlatformTest {
    private val quota = "https://chatgpt.com/backend-api/wham/usage"
    private val auth = "https://auth.openai.com"

    @Test
    fun onlyPinnedPassiveAccountEndpointsAndMethodsAreAccepted() {
        val body = "{}".toByteArray()
        for (path in listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token", "/oauth/revoke")) {
            assertEquals(auth + path, AccountRequestPolicy.prepare("POST", auth + path, "{}", body).url.toString())
            rejected { AccountRequestPolicy.prepare("GET", auth + path, "{}", null) }
        }
        assertEquals(quota, AccountRequestPolicy.prepare("GET", quota, "{}", null).url.toString())
        rejected { AccountRequestPolicy.prepare("POST", quota, "{}", body) }
        rejected { AccountRequestPolicy.prepare("GET", quota, "{}", body) }
        for (url in listOf(
            "http://chatgpt.com/backend-api/wham/usage",
            "$quota?reserve=true",
            "$quota?",
            "$quota#fragment",
            "$quota/",
            "https://chatgpt.com:443/backend-api/wham/usage",
            "https://token@chatgpt.com/backend-api/wham/usage",
            "https://chatgpt.com.evil.example/backend-api/wham/usage",
            "https://chatgpt.com/backend-api/wham/%75sage",
            "https://chatgpt.com/backend-api/wham/../wham/usage",
            "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume",
            "$auth/codex/device",
        )) rejected { AccountRequestPolicy.prepare("GET", url, "{}", null) }
    }

    @Test
    fun identityHeadersAreRestrictedToQuotaAndRoutingHeadersNeverCrossBoundary() {
        val identity = headers("Authorization" to "Bearer test.synthetic_token", "ChatGPT-Account-Id" to "account-fixture")
        val allowed = AccountRequestPolicy.prepare("GET", quota, identity, null)
        assertEquals("Bearer test.synthetic_token", allowed.headers["authorization"]?.single())
        assertEquals("account-fixture", allowed.headers["chatgpt-account-id"]?.single())
        rejected { AccountRequestPolicy.prepare("POST", "$auth/oauth/token", identity, "{}".toByteArray()) }
        for (name in listOf("Host", "Cookie", "Cookie2", "Proxy-Authorization", "Proxy-Connection", "X-Forwarded-Host", "Forwarded", "Connection", "Transfer-Encoding", "x-openai-codex-luna-reserve")) {
            rejected { AccountRequestPolicy.prepare("GET", quota, headers(name to "value"), null) }
        }
        rejected { AccountRequestPolicy.prepare("GET", quota, headers("Authorization" to "Basic synthetic"), null) }
        rejected { AccountRequestPolicy.prepare("GET", quota, headers("ChatGPT-Account-Id" to "has whitespace"), null) }
    }

    @Test
    fun pinnedDefaultHeadersPassWhileFramingIsRecomputed() {
        val input = headers(
            "User-Agent" to "codex-fixture/1.0",
            "originator" to "codex_cli_rs",
            "OpenAI-Residency" to "us",
            "Content-Type" to "application/json",
            "Cache-Control" to "no-cache",
            "X-OpenAI-Fedramp" to "true",
            "Accept-Encoding" to "gzip",
            "Content-Length" to "9999",
        )
        val request = AccountRequestPolicy.prepare("GET", quota, input, null)
        assertEquals("application/json", request.headers["content-type"]?.single())
        assertEquals("us", request.headers["openai-residency"]?.single())
        assertFalse(request.headers.containsKey("content-length"))
        assertFalse(request.headers.containsKey("accept-encoding"))
    }

    @Test
    fun malformedOrExcessiveHeaderAndBodyInputsFailBeforeNetwork() {
        for (input in listOf(
            "not-json",
            "{\"accept\":\"application/json\"}",
            "{\"accept\":[4]}",
            "{\"accept\":[]}",
            headers("accept" to "application/json\r\nHost: elsewhere"),
            headers("authorization" to "Bearer " + "a".repeat(40_000)),
            "{\"accept\":[\"a\"],\"Accept\":[\"b\"]}",
        )) rejected { AccountRequestPolicy.prepare("GET", quota, input, null) }
        rejected { AccountRequestPolicy.prepare("POST", "$auth/oauth/token", "{}", ByteArray(65_537)) }
    }

    @Test
    fun envelopeRoundTripUsesFreshCiphertextAndRejectsDifferentInstallKey() {
        val key = newKey()
        val secret = "synthetic account storage test marker".toByteArray()
        val first = AccountSecretEnvelope.encrypt(secret, key)
        val second = AccountSecretEnvelope.encrypt(secret, key)
        assertArrayEquals(secret, AccountSecretEnvelope.decrypt(first, key))
        assertArrayEquals(secret, AccountSecretEnvelope.decrypt(second, key))
        assertNotEquals(first.toList(), second.toList())
        assertFalse(String(first, Charsets.ISO_8859_1).contains(String(secret)))
        rejected { AccountSecretEnvelope.decrypt(first, newKey()) }
        rejected { AccountSecretEnvelope.decrypt(secret, key) }
    }

    @Test
    fun envelopeRejectsTamperingTruncationAndUnboundedLengths() {
        val key = newKey()
        val envelope = AccountSecretEnvelope.encrypt("synthetic".toByteArray(), key)
        for (index in listOf(0, 4, 5, 6, 10, envelope.lastIndex)) {
            val tampered = envelope.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            rejected { AccountSecretEnvelope.decrypt(tampered, key) }
        }
        for (length in listOf(0, 9, 21, envelope.size - 1)) {
            rejected { AccountSecretEnvelope.decrypt(envelope.copyOf(length), key) }
        }
        rejected { AccountSecretEnvelope.decrypt(envelope + byteArrayOf(0), key) }
        val unbounded = envelope.copyOf()
        ByteBuffer.wrap(unbounded).putInt(6, Int.MAX_VALUE)
        rejected { AccountSecretEnvelope.decrypt(unbounded, key) }
        rejected { AccountSecretEnvelope.encrypt(ByteArray(65_537), key) }
        rejected { AccountSecretEnvelope.encrypt(byteArrayOf(), key) }
        val maximum = ByteArray(AccountSecretEnvelope.MAX_SECRET_BYTES) { 42 }
        assertArrayEquals(maximum, AccountSecretEnvelope.decrypt(AccountSecretEnvelope.encrypt(maximum, key), key))
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun headers(vararg values: Pair<String, String>): String = JSONObject().apply {
        values.forEach { (name, value) -> put(name, JSONArray().put(value)) }
    }.toString()

    private fun rejected(operation: () -> Unit) {
        try {
            operation()
            fail("Invalid account input must fail closed")
        } catch (_: Exception) {
            // Only rejection matters; no input or platform exception is logged.
        }
    }
}
