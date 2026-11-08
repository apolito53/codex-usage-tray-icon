package com.apolito.codexusage.preview

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** The native account library's only network and credential-storage boundary. */
open class AccountPlatform(context: Context) {
    private val app = context.applicationContext

    /** Native calls this on a worker. Error messages deliberately retain no platform cause. */
    open fun request(method: String, url: String, headers: String, body: ByteArray?): String {
        var connection: HttpsURLConnection? = null
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            requireWorker()
            val request = AccountRequestPolicy.prepare(method, url, headers, body)
            // This app has no WebView or cookie store. Refuse an ambient handler
            // rather than allowing its cookies into account requests or changing
            // process-global networking state while another request is running.
            check(CookieHandler.getDefault() == null)
            val deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
            val https = request.url.openConnection() as HttpsURLConnection
            connection = https
            // Keep the platform's trust manager and hostname verifier untouched.
            https.instanceFollowRedirects = false
            https.connectTimeout = CONNECT_TIMEOUT_MS
            https.readTimeout = READ_TIMEOUT_MS
            https.useCaches = false
            https.requestMethod = method
            https.setRequestProperty("Accept-Encoding", "identity")
            request.headers.forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) https.setRequestProperty(name, value) else https.addRequestProperty(name, value)
                }
            }
            // HttpsURLConnection has no public write timeout. A total deadline
            // also disconnects a stuck upload/read; per-operation timeouts remain.
            timeout = DEADLINES.schedule({ https.disconnect() }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            request.body?.let { bytes ->
                https.doOutput = true
                https.setFixedLengthStreamingMode(bytes.size)
                https.outputStream.use { it.write(bytes) }
            }
            val status = https.responseCode
            check(status in 100..599 && status !in 300..399)
            check(SystemClock.elapsedRealtime() <= deadline)
            val responseHeaders = JSONObject()
            var headerBytes = 0
            https.headerFields.forEach { (name, values) ->
                if (name != null && !name.equals("set-cookie", true) && !name.equals("set-cookie2", true)) {
                    check(name.length <= 256 && values.size <= 32)
                    headerBytes += name.length
                    val array = JSONArray()
                    values.forEach { value ->
                        headerBytes += value.length
                        check(headerBytes <= AccountRequestPolicy.MAX_HEADERS_BYTES)
                        array.put(value)
                    }
                    responseHeaders.put(name.lowercase(Locale.ROOT), array)
                }
            }
            check(https.contentLengthLong <= MAX_RESPONSE_BYTES)
            val stream = if (status >= 400) https.errorStream else https.inputStream
            val response = stream?.use { boundedRead(it, MAX_RESPONSE_BYTES, deadline) } ?: byteArrayOf()
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response)).toString()
            return JSONObject().put("status", status).put("body", text).put("headers", responseHeaders).toString()
        } catch (_: Exception) {
            throw IOException("Account request failed")
        } finally {
            timeout?.cancel(false)
            try { connection?.disconnect() } catch (_: Exception) { /* Keep failures sanitized. */ }
        }
    }

    /** A null result means only that no committed encrypted account record exists. */
    fun loadSecret(): ByteArray? = secureOperation {
        val directory = directory(create = false)
        val record = File(directory, RECORD_NAME)
        if (!regularRecordExists(record)) return@secureOperation null
        val key = existingKey(keyStore()) ?: error("Missing account key")
        val envelope = FileInputStream(record).use { boundedRead(it, AccountSecretEnvelope.MAX_ENVELOPE_BYTES) }
        AccountSecretEnvelope.decrypt(envelope, key)
    }

    fun saveSecret(bytes: ByteArray) = secureOperation {
        require(bytes.size in 1..AccountSecretEnvelope.MAX_SECRET_BYTES)
        val directory = directory(create = true)
        val record = File(directory, RECORD_NAME)
        val pending = File(directory, PENDING_NAME)
        val store = keyStore()
        val key = existingKey(store) ?: run {
            // A missing installation key must never replace surviving ciphertext.
            check(!regularRecordExists(record) && !Files.exists(pending.toPath(), LinkOption.NOFOLLOW_LINKS))
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
        }
        val envelope = AccountSecretEnvelope.encrypt(bytes, key)
        var committed = false
        try {
            // Same-directory ciphertext write, fsync, atomic rename, directory
            // fsync follows AndroidCredentialVault.java in the project's
            // android/nativeprobe storage package's durability ordering.
            // No lab namespace, plaintext mode, or diagnostic hooks are reused.
            if (Files.exists(pending.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(pending.toPath(), LinkOption.NOFOLLOW_LINKS))
            }
            FileOutputStream(pending).use { output ->
                output.write(envelope)
                output.fd.sync()
            }
            Os.rename(pending.absolutePath, record.absolutePath)
            committed = true
            syncDirectory(directory)
        } finally {
            if (!committed) Files.deleteIfExists(pending.toPath())
        }
    }

    /** Deletion works without decrypting a damaged or installation-mismatched record. */
    fun deleteSecret() = secureOperation {
        val directory = directory(create = false)
        var storageFailure: Exception? = null
        try {
            val removed = Files.deleteIfExists(File(directory, RECORD_NAME).toPath())
            val removedPending = Files.deleteIfExists(File(directory, PENDING_NAME).toPath())
            if (removed || removedPending) syncDirectory(directory)
        } catch (failure: Exception) {
            storageFailure = failure
        }
        // Also remove the dedicated key. If file deletion failed, this makes
        // the surviving ciphertext unusable; report any failure to the caller.
        keyStore().deleteEntry(KEY_ALIAS)
        if (storageFailure != null) throw storageFailure
    }

    private fun directory(create: Boolean): File {
        val directory = File(app.noBackupFilesDir, "account-v1")
        if (create && !directory.exists()) check(directory.mkdirs())
        check(!Files.isSymbolicLink(directory.toPath()))
        if (directory.exists()) check(directory.isDirectory)
        return directory
    }

    private fun regularRecordExists(record: File): Boolean = try {
        val attributes = Files.readAttributes(record.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.size() <= AccountSecretEnvelope.MAX_ENVELOPE_BYTES)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(store: KeyStore): SecretKey? = store.getKey(KEY_ALIAS, null)?.let {
        check(it is SecretKey)
        it
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun <T> secureOperation(operation: () -> T): T = try {
        requireWorker()
        synchronized(STORAGE_LOCK) { operation() }
    } catch (_: Exception) {
        throw IOException("Secure account storage unavailable")
    }

    private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper())

    companion object {
        private const val KEY_ALIAS = "com.apolito.codexusage.account.v1"
        private const val RECORD_NAME = "account.bin"
        private const val PENDING_NAME = "account.pending"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private const val MAX_RESPONSE_BYTES = 1_048_576
        private val STORAGE_LOCK = Any()
        private val DEADLINES = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AccountRequestDeadline").apply { isDaemon = true }
        }

        private fun boundedRead(stream: InputStream, maximum: Int, deadline: Long = Long.MAX_VALUE): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                check(SystemClock.elapsedRealtime() <= deadline)
                val count = stream.read(buffer)
                if (count == -1) return output.toByteArray()
                check(output.size() + count <= maximum)
                output.write(buffer, 0, count)
            }
        }
    }
}

internal data class PreparedAccountRequest(val url: URL, val headers: Map<String, List<String>>, val body: ByteArray?)

/** Exact strings avoid URL parser ambiguity, encoded paths, queries, and redirects. */
internal object AccountRequestPolicy {
    const val MAX_HEADERS_BYTES = 32_768
    private const val MAX_BODY_BYTES = 65_536
    private const val QUOTA_URL = "https://chatgpt.com/backend-api/wham/usage"
    // Pinned OpenAI Codex 8f195c93: login/src/device_code_auth.rs,
    // login/src/auth/manager.rs, backend-client/src/client/rate_limit_resets.rs.
    // Passive usage adds no query and never sets the Luna Reserve opt-in header.
    private val AUTH_URLS = setOf(
        "https://auth.openai.com/api/accounts/deviceauth/usercode",
        "https://auth.openai.com/api/accounts/deviceauth/token",
        "https://auth.openai.com/oauth/token",
        "https://auth.openai.com/oauth/revoke",
    )
    private val COMMON_HEADERS = setOf("accept", "content-type", "cache-control", "user-agent", "originator", "openai-residency")
    private val QUOTA_HEADERS = setOf("authorization", "chatgpt-account-id", "x-openai-fedramp")
    private val RECOMPUTED_HEADERS = setOf("content-length", "accept-encoding")

    fun prepare(method: String, url: String, encodedHeaders: String, body: ByteArray?): PreparedAccountRequest {
        val quota = url == QUOTA_URL
        require((quota && method == "GET") || (url in AUTH_URLS && method == "POST"))
        require(body == null || body.size <= MAX_BODY_BYTES)
        require(!quota || body == null || body.isEmpty())
        require(encodedHeaders.toByteArray(Charsets.UTF_8).size <= MAX_HEADERS_BYTES)
        val input = JSONObject(encodedHeaders)
        require(input.length() <= 24)
        val headers = linkedMapOf<String, List<String>>()
        val seen = mutableSetOf<String>()
        for (original in input.keys()) {
            val name = original.lowercase(Locale.ROOT)
            require(seen.add(name))
            require(name in COMMON_HEADERS || (quota && name in QUOTA_HEADERS) || name in RECOMPUTED_HEADERS)
            val values = input.get(original) as? JSONArray ?: error("Invalid account headers")
            require(values.length() in 1..8)
            val entries = (0 until values.length()).map { index ->
                val value = values.get(index) as? String ?: error("Invalid account header")
                require(value.length <= 16_384 && value.all { it in ' '..'~' || it == '\t' })
                value
            }
            if (name in QUOTA_HEADERS) require(entries.size == 1)
            if (name == "authorization") require(entries.single().matches(Regex("Bearer [A-Za-z0-9._~+/-]+=*")))
            if (name == "chatgpt-account-id") require(entries.single().isNotEmpty() && entries.single().none { it.isWhitespace() })
            if (name == "x-openai-fedramp") require(entries.single() == "true")
            if (name == "openai-residency") require(entries.size == 1 && entries.single() == "us")
            if (name !in RECOMPUTED_HEADERS) headers[name] = entries
        }
        return PreparedAccountRequest(URL(url), headers, if (quota) null else body?.copyOf())
    }
}

/**
 * Versioned AES-GCM record; the format header and app-specific domain are AAD.
 * Key use follows Android's documented Keystore AES/GCM construction:
 * https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec
 */
internal object AccountSecretEnvelope {
    const val MAX_SECRET_BYTES = 65_536
    private const val MAGIC = 0x434D4131 // CMA1: Codex Meter Account, format 1.
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 10
    const val MAX_ENVELOPE_BYTES = HEADER_BYTES + IV_BYTES + MAX_SECRET_BYTES + TAG_BYTES
    private val DOMAIN = "com.apolito.codexusage.preview/account-secret/v1".toByteArray(Charsets.UTF_8)

    fun encrypt(secret: ByteArray, key: SecretKey): ByteArray {
        require(secret.size in 1..MAX_SECRET_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // Keystore supplies a fresh randomized IV.
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).put(VERSION).put(IV_BYTES.toByte())
            .putInt(secret.size + TAG_BYTES).array()
        cipher.updateAAD(header)
        cipher.updateAAD(DOMAIN)
        return header + iv + cipher.doFinal(secret)
    }

    fun decrypt(envelope: ByteArray, key: SecretKey): ByteArray {
        require(envelope.size in (HEADER_BYTES + IV_BYTES + TAG_BYTES + 1)..MAX_ENVELOPE_BYTES)
        val input = ByteBuffer.wrap(envelope)
        require(input.int == MAGIC && input.get() == VERSION && input.get().toInt() == IV_BYTES)
        val encryptedLength = input.int
        require(encryptedLength in (TAG_BYTES + 1)..(MAX_SECRET_BYTES + TAG_BYTES))
        require(input.remaining() == IV_BYTES + encryptedLength)
        val iv = ByteArray(IV_BYTES).also { input.get(it) }
        val encrypted = ByteArray(encryptedLength).also { input.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(envelope.copyOfRange(0, HEADER_BYTES))
        cipher.updateAAD(DOMAIN)
        return cipher.doFinal(encrypted)
    }
}
