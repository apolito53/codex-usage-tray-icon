package com.apolito.codexusage.preview

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Controls worker and main-loop ordering to exercise real account-state boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class AccountControllerTest {
    private lateinit var app: Application
    private lateinit var io: QueuedExecutor
    private lateinit var poll: QueuedExecutor
    private lateinit var backend: FakeBackend
    private lateinit var controller: AccountController

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("account-usage", Context.MODE_PRIVATE).edit().clear().commit()
        PreviewNotification.hide(app)
        MeterRefreshJob.cancel(app)
        io = QueuedExecutor()
        poll = QueuedExecutor()
        backend = FakeBackend()
        controller = AccountController(app, backend, io, poll) { 1_799_000_000_000L }
    }

    @Test
    fun networkFailureKeepsKnownZeroStaleButSuccessfulUnknownClearsIt() {
        backend.initializeReply = connected()
        backend.reads.add(reading(0))
        startAndFinishInitialReading()
        assertEquals(0, controller.state.reading?.remaining)
        assertFalse(controller.state.stale)
        assertTrue(PreviewNotification.show(app, controller.state))

        backend.reads.add("""{"ok":false,"error":"network"}""")
        controller.refresh()
        io.runNext()
        drainMain()
        assertEquals("A failed request keeps the known zero", 0, controller.state.reading?.remaining)
        assertTrue(controller.state.stale)
        assertTrue(controller.state.message!!.contains("connection", ignoreCase = true))
        assertEquals(0, cachedReading().getInt("remaining"))
        assertTrue(notification().extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("0%"))
        assertTrue(notification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("STALE / OFFLINE"))

        backend.reads.add(reading(null))
        controller.refresh()
        io.runNext()
        drainMain()
        assertNotNull("A successful unknown is still a fresh observation", controller.state.reading)
        assertNull(controller.state.reading!!.remaining)
        assertNull(controller.state.reading!!.resetsAtSeconds)
        assertFalse(controller.state.stale)
        assertNull(controller.state.message)
        assertTrue("The old zero must not survive in the cached reading", cachedReading().isNull("remaining"))
        val title = notification().extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title.contains("unavailable"))
        assertFalse(title.contains("0%"))
    }

    @Test
    fun signOutWinsAgainstAnAlreadyCompletedOlderRefreshCallback() {
        backend.initializeReply = connected()
        backend.reads.add(reading(0))
        startAndFinishInitialReading()
        assertTrue(PreviewNotification.show(app, controller.state))
        assertEquals(0, cachedReading().getInt("remaining"))

        backend.reads.add(reading(42))
        controller.refresh()
        io.runNext() // Work completed, but its result is still queued on the main looper.
        controller.signOut()
        assertFalse(controller.state.connected)
        assertNull(controller.state.reading)
        assertNull(cachedText())
        assertFalse(PreviewNotification.isVisible(app))

        drainMain() // Deliver the old successful refresh after sign-out started.
        assertFalse(controller.state.connected)
        assertNull(controller.state.reading)
        assertNull(cachedText())
        assertFalse(PreviewNotification.isVisible(app))
        io.runNext() // Finish the real controller's sign-out operation.
        drainMain()
        assertFalse(controller.state.connected)
        assertFalse(controller.state.busy)
        assertNull(controller.state.reading)
        assertNull(cachedText())
        assertFalse(PreviewNotification.isVisible(app))
    }

    @Test
    fun cancelWinsAgainstAnAlreadyCompletedOlderLoginCallback() {
        controller.start()
        io.runNext()
        drainMain()
        controller.signIn()
        io.runNext()
        drainMain()
        assertEquals("FIXTURE-CODE", controller.state.challenge?.userCode)

        poll.runNext() // The login result is queued but not yet delivered.
        controller.cancelLogin()
        drainMain()
        assertFalse("A late completion must not reconnect the account", controller.state.connected)
        assertNull(controller.state.reading)
        assertNull(controller.state.challenge)
        assertNull(cachedText())
        assertFalse(PreviewNotification.isVisible(app))
        io.runNext()
        drainMain()
        assertFalse(controller.state.connected)
        assertFalse(controller.state.busy)
        assertNull(controller.state.reading)
        assertNull(cachedText())
        assertEquals("The stale completion must not schedule a usage read", 0, backend.readCount)
        assertEquals(0, io.pending)
    }

    private fun startAndFinishInitialReading() {
        controller.start()
        io.runNext()
        drainMain()
        io.runNext()
        drainMain()
        assertTrue(controller.state.connected)
        assertFalse(controller.state.busy)
    }

    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()
    private fun cachedText() = app.getSharedPreferences("account-usage", Context.MODE_PRIVATE).getString("reading", null)
    private fun cachedReading() = JSONObject(requireNotNull(cachedText()))
    private fun notification() = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val pending get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() = checkNotNull(tasks.pollFirst()) { "No queued account operation" }.run()
    }

    private class FakeBackend : AccountBackend {
        var initializeReply = """{"ok":true,"connected":false}"""
        val reads = ArrayDeque<String>()
        var readCount = 0
        override fun initialize() = initializeReply
        override fun startLogin() = """{"ok":true,"attempt":7,"userCode":"FIXTURE-CODE","verificationUrl":"https://auth.openai.com/codex/device"}"""
        override fun finishLogin(attempt: Long) = connected()
        override fun cancelLogin(attempt: Long) = """{"ok":true,"connected":false}"""
        override fun readUsage(): String {
            readCount += 1
            return checkNotNull(reads.pollFirst()) { "Unexpected usage request" }
        }
        override fun signOut() = """{"ok":true,"connected":false}"""
    }

    companion object {
        private fun connected() = JSONObject().put("ok", true).put("connected", true)
            .put("accountKey", "a".repeat(64)).toString()
        private fun reading(value: Int?) = JSONObject().put("ok", true)
            .put("remaining", value ?: JSONObject.NULL)
            .put("resetsAtSeconds", if (value == null) JSONObject.NULL else 1_800_000_000L).toString()
    }
}
