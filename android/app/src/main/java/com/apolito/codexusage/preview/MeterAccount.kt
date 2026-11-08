package com.apolito.codexusage.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class LoginChallenge(val userCode: String, val verificationUrl: String)
data class UsageReading(val remaining: Int?, val resetsAtSeconds: Long?, val observedAtMillis: Long)
data class MeterState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val challenge: LoginChallenge? = null,
    val reading: UsageReading? = null,
    val stale: Boolean = false,
    val message: String? = null,
    val canForget: Boolean = false,
)

internal interface AccountBackend {
    fun initialize(): String
    fun startLogin(): String
    fun finishLogin(attempt: Long): String
    fun cancelLogin(attempt: Long): String
    fun readUsage(): String
    fun signOut(): String
}

private class PhoneBackend(private val context: Context) : AccountBackend {
    private val platform by lazy { AccountPlatform(context) }
    override fun initialize(): String {
        val home = File(context.noBackupFilesDir, "codex-account")
        if (!home.isDirectory && !home.mkdirs()) throw java.io.IOException("Account directory unavailable")
        return NativeAccount.initialize(platform, home.absolutePath)
    }
    override fun startLogin() = NativeAccount.startLogin()
    override fun finishLogin(attempt: Long) = NativeAccount.finishLogin(attempt)
    override fun cancelLogin(attempt: Long) = NativeAccount.cancelLogin(attempt)
    override fun readUsage() = NativeAccount.readUsage()
    override fun signOut() = NativeAccount.signOut()
}

object MeterAccount {
    // Only applicationContext is retained. Activity observers unregister in onStop.
    @Suppress("StaticFieldLeak")
    private var instance: AccountController? = null

    private fun controller(context: Context): AccountController {
        check(Looper.myLooper() == Looper.getMainLooper())
        return instance ?: context.applicationContext.let { app ->
            AccountController(app, PhoneBackend(app)).also {
                instance = it
                it.start()
            }
        }
    }

    fun state(context: Context) = controller(context).state
    fun observe(context: Context, listener: (MeterState) -> Unit) = controller(context).observe(listener)
    fun signIn(context: Context) = controller(context).signIn()
    fun resumeLogin(context: Context) = controller(context).resume()
    fun cancelLogin(context: Context) = controller(context).cancelLogin()
    fun refresh(context: Context) = controller(context).refresh()
    fun signOut(context: Context) = controller(context).signOut()
    internal fun backgroundRefresh(context: Context, finished: () -> Unit) = controller(context).refresh(finished)
}

/** State changes are serialized on the main looper. Only the device-code wait needs a second worker. */
internal class AccountController(
    private val app: Context,
    private val backend: AccountBackend,
    private val io: Executor = Executors.newSingleThreadExecutor(),
    private val poll: Executor = Executors.newSingleThreadExecutor(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())
    private val cache = app.getSharedPreferences("account-usage", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<(MeterState) -> Unit>()
    private val waitingJobs = mutableListOf<() -> Unit>()
    private var initialized = false
    private var triedInitialization = false
    private var generation = 0L
    private var attempt: Long? = null
    private var accountKey: String? = null
    private var refreshing = false
    var state = MeterState()
        private set

    fun observe(listener: (MeterState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    fun start() {
        publish(MeterState(busy = true))
        val owner = generation
        run(io, backend::initialize) { reply ->
            if (owner != generation) return@run
            triedInitialization = true
            initialized = reply.succeeded()
            if (!initialized) {
                signedOut(errorMessage(reply), canForget = reply.needsLocalRecovery())
                return@run
            }
            if (!reply.optBoolean("connected")) {
                signedOut(null)
                return@run
            }
            accountKey = keyFrom(reply)
            val last = cachedReading()
            publish(MeterState(connected = true, reading = last, stale = last != null))
            MeterRefreshJob.schedule(app)
            refresh()
        }
    }

    fun signIn() {
        if (state.busy || state.connected) return
        if (!initialized) {
            // A transient secure-storage failure can clear when the phone is unlocked.
            val owner = generation
            publish(state.copy(busy = true, message = null))
            run(io, backend::initialize) { reply ->
                if (owner != generation) return@run
                initialized = reply.succeeded()
                if (!initialized) {
                    signedOut(errorMessage(reply), canForget = reply.needsLocalRecovery())
                } else if (reply.optBoolean("connected")) {
                    connected(reply)
                } else {
                    startChallenge()
                }
            }
        } else startChallenge()
    }

    private fun startChallenge() {
        val owner = ++generation
        publish(MeterState(busy = true))
        run(io, backend::startLogin) { reply ->
            val id = reply.optLong("attempt", -1)
            if (owner != generation) {
                if (id >= 0) run(io, { backend.cancelLogin(id) }) {}
                return@run
            }
            val code = reply.optString("userCode")
            val url = reply.optString("verificationUrl")
            if (!reply.succeeded() || id < 0 || code.isBlank() || code.length > 64 || url != DEVICE_URL) {
                if (id >= 0) run(io, { backend.cancelLogin(id) }) {}
                signedOut(if (reply.succeeded()) "OpenAI returned an unreadable sign-in code. Try signing in again." else errorMessage(reply))
                return@run
            }
            attempt = id
            publish(MeterState(busy = true, challenge = LoginChallenge(code, url)))
            run(poll, { backend.finishLogin(id) }) waiting@{ result ->
                if (owner != generation) return@waiting
                attempt = null
                if (result.succeeded()) {
                    connected(result)
                } else {
                    if (result.needsLocalRecovery()) initialized = false
                    signedOut(errorMessage(result), canForget = result.needsLocalRecovery())
                }
            }
        }
    }

    private fun connected(reply: JSONObject) {
        accountKey = keyFrom(reply)
        cache.edit().clear().apply()
        publish(MeterState(connected = true))
        Log.i(TAG, "Phone sign-in completed")
        MeterRefreshJob.schedule(app)
        refresh()
    }

    fun resume() {
        val observed = state.reading?.observedAtMillis
        if (state.connected && !state.busy && (observed == null || clock() - observed >= MeterRefreshJob.INTERVAL)) refresh()
    }

    fun cancelLogin() {
        val id = attempt ?: return
        val owner = ++generation
        attempt = null
        publish(MeterState(busy = true))
        run(io, { backend.cancelLogin(id) }) { reply ->
            if (owner == generation) {
                // A grant that committed just before cancellation belongs to this attempt.
                // Ask the native session which state actually won that race.
                if (reply.optBoolean("connected")) signOut()
                else signedOut(if (reply.succeeded() || reply.optString("error") == "cancelled") "Sign-in cancelled." else errorMessage(reply))
            }
        }
    }

    fun refresh(finished: (() -> Unit)? = null) {
        if (finished != null) waitingJobs += finished
        if (!triedInitialization || refreshing) return
        if (!state.connected || state.busy) {
            finishJobs()
            return
        }
        val owner = generation
        refreshing = true
        val old = state.reading?.let { clock() - it.observedAtMillis >= MeterRefreshJob.INTERVAL * 2 } == true
        publish(state.copy(busy = true, stale = state.stale || old, message = null))
        run(io, backend::readUsage) { reply ->
            if (owner != generation) return@run
            refreshing = false
            if (reply.succeeded()) {
                val remaining = if (reply.isNull("remaining")) null else reply.getInt("remaining").takeIf { it in 0..100 }
                val reset = if (reply.isNull("resetsAtSeconds")) null else reply.optLong("resetsAtSeconds").takeIf { it > 0 }
                val reading = UsageReading(remaining, reset, clock())
                cacheReading(reading)
                publish(state.copy(busy = false, reading = reading, stale = false, message = null))
                Log.i(TAG, "Weekly allowance refreshed")
            } else if (reply.optString("error") in setOf("needs_login", "signed_out")) {
                signedOut(errorMessage(reply))
            } else if (reply.optString("error") == "storage") {
                initialized = false
                signedOut(errorMessage(reply), canForget = true)
            } else {
                publish(state.copy(busy = false, stale = state.reading != null, message = errorMessage(reply)))
                Log.w(TAG, "Allowance refresh unavailable")
            }
            finishJobs()
        }
    }

    fun signOut() {
        val owner = ++generation
        val pending = attempt
        attempt = null
        accountKey = null
        refreshing = false
        cache.edit().clear().apply()
        MeterRefreshJob.cancel(app)
        PreviewNotification.hide(app)
        publish(MeterState(busy = true))
        finishJobs()
        run(io, {
            if (pending != null) backend.cancelLogin(pending)
            backend.signOut()
        }) { reply ->
            if (owner != generation) return@run
            if (!reply.succeeded()) {
                initialized = false
                signedOut(errorMessage(reply), canForget = true)
            } else {
                initialized = true
                signedOut(if (!reply.optBoolean("revocationConfirmed", true))
                    "Signed out on this phone. OpenAI could not confirm revocation." else "Signed out on this phone.")
            }
        }
    }

    private fun signedOut(message: String?, canForget: Boolean = false) {
        refreshing = false
        accountKey = null
        cache.edit().clear().apply()
        MeterRefreshJob.cancel(app)
        publish(MeterState(message = message, canForget = canForget))
        finishJobs()
    }

    private fun publish(value: MeterState) {
        state = value
        // Leave a previously active notification alone while the saved session is first being read.
        if (triedInitialization) PreviewNotification.updateIfVisible(app, value)
        listeners.toList().forEach { it(value) }
    }

    private fun finishJobs() {
        val callbacks = waitingJobs.toList()
        waitingJobs.clear()
        callbacks.forEach { it() }
    }

    private fun keyFrom(reply: JSONObject) =
        reply.optString("accountKey").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }

    private fun cachedReading(): UsageReading? {
        val value = cache.getString("reading", null) ?: return null
        val key = accountKey ?: return null
        return try {
            val json = JSONObject(value)
            if (json.getString("owner") != key) null else UsageReading(
                if (json.isNull("remaining")) null else json.getInt("remaining").takeIf { it in 0..100 },
                if (json.isNull("reset")) null else json.getLong("reset"),
                json.getLong("observed"),
            )
        } catch (_: org.json.JSONException) { null }
    }

    private fun cacheReading(reading: UsageReading) {
        val key = accountKey ?: return
        val json = JSONObject().put("owner", key).put("remaining", reading.remaining ?: JSONObject.NULL)
            .put("reset", reading.resetsAtSeconds ?: JSONObject.NULL).put("observed", reading.observedAtMillis)
        cache.edit().putString("reading", json.toString()).apply()
    }

    private fun run(executor: Executor, work: () -> String, done: (JSONObject) -> Unit) {
        executor.execute {
            val result = try {
                JSONObject(work())
            } catch (_: LinkageError) {
                JSONObject().put("error", "native")
            } catch (_: Exception) {
                JSONObject().put("error", "unavailable")
            }
            main.post { done(result) }
        }
    }

    private fun JSONObject.succeeded() = optBoolean("ok", false)
    private fun JSONObject.needsLocalRecovery() = optString("error") in setOf("storage", "needs_login")
    private fun errorMessage(reply: JSONObject) = when (reply.optString("error")) {
        "cancelled" -> "Sign-in cancelled."
        "expired" -> "The sign-in code expired. Request a new code."
        "signed_out", "needs_login" -> "Your ChatGPT session ended. Sign in again."
        "rate_limited" -> "OpenAI asked the meter to wait. Try again later."
        "storage" -> "The phone could not open its saved sign-in. Unlock the phone and try again, or forget the saved sign-in."
        "native" -> "This installation could not load the account component."
        "network" -> "Could not reach OpenAI. Check your connection and try again."
        else -> "OpenAI could not complete this request. Try again."
    }

    companion object {
        private const val DEVICE_URL = "https://auth.openai.com/codex/device"
        private const val TAG = "CodexUsageMeter"
    }
}
