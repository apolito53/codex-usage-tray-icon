package com.apolito.codexusage.preview

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Exercises the actual launch lifecycle, including the platform window setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class MainActivityStartupTest {
    @Test
    fun coldLaunchAndRecreationReachUsableScreenWithoutPostingNotification() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertScreenReady(controller.get())

            controller.recreate()
            assertScreenReady(controller.get())
        }
    }

    @Test
    fun launchOffersBrowserSignInWithoutCredentialFieldsOrSampleReadings() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val views = descendants(controller.get().findViewById(android.R.id.content)).toList()
            assertTrue(views.any { it is Button && it.text == "Sign in with ChatGPT" })
            assertTrue(views.any { it is Button && it.text == "Try demo" })
            assertFalse("Credentials belong in the browser", views.any { it is EditText })
            assertFalse("Fixtures must not become genuine readings", views.any {
                it is TextView && (it.text.contains("72%") || it.text.contains("Choose a sample"))
            })
            val show = views.filterIsInstance<Button>().first { it.text == "Show meter" }
            assertFalse("Sign-in is required before showing a meter", show.isEnabled)
        }
    }

    @Test
    fun demoEntryRefreshNotificationAndExitWorkWithoutAccount() {
        val app = notificationContext()
        DemoMeter.exit(app)
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { controller ->
                controller.setup()
                var activity = controller.get()
                val demo = button(activity, "Try demo")
                // The real account is initialized on a worker; a JVM test has no
                // native library, but its failure must still leave demo available.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!demo.isEnabled && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertTrue("Demo becomes available without a saved account", demo.isEnabled)
                demo.performClick()
                assertTrue(DemoMeter.isActive(app))
                assertTrue(screenText(activity).contains("72% remaining · demo"))
                assertTrue(screenText(activity).contains("EXAMPLE DATA · not your account"))
                assertEquals(View.GONE, button(activity, "Sign in with ChatGPT").visibility)

                button(activity, "Show meter").performClick()
                var posted = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
                assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Demo · 72%"))
                assertTrue(posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("EXAMPLE DATA"))

                button(activity, "Refresh").performClick()
                assertTrue(screenText(activity).contains("64% remaining · demo"))
                posted = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
                assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Demo · 64%"))

                controller.recreate()
                activity = controller.get()
                assertTrue(screenText(activity).contains("64% remaining · demo"))
                button(activity, "Exit demo").performClick()
                assertFalse(DemoMeter.isActive(app))
                assertFalse(PreviewNotification.isVisible(app))
                assertEquals(View.VISIBLE, button(activity, "Sign in with ChatGPT").visibility)
            }
        } finally {
            DemoMeter.exit(app)
            PreviewNotification.hide(app)
        }
    }

    @Test
    fun demoNotificationIsIndependentOfRealAccountAndDoesNotResurrect() {
        val app = notificationContext()
        val accountCache = app.getSharedPreferences("account-usage", Context.MODE_PRIVATE)
        accountCache.edit().putString("reading", "real-reading-sentinel").commit()
        try {
            DemoMeter.start(app, 1_800_000_000_000)
            assertTrue(PreviewNotification.showDemo(app, requireNotNull(DemoMeter.reading(app))))
            PreviewNotification.updateIfVisible(app, MeterState())
            assertTrue("Signed-out account updates must not dismiss the demo", PreviewNotification.isDemoVisible(app))
            DemoMeter.refresh(app, 1_800_000_001_000)
            PreviewNotification.updateDemoIfVisible(app, requireNotNull(DemoMeter.reading(app)))
            val title = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
                .notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            assertTrue(title.contains("Demo · 64%"))
            PreviewNotification.hide(app)
            PreviewNotification.updateDemoIfVisible(app, requireNotNull(DemoMeter.reading(app)))
            assertFalse("Refresh must respect a dismissed demo", PreviewNotification.isVisible(app))
            DemoMeter.exit(app)
            assertEquals("real-reading-sentinel", accountCache.getString("reading", null))
            assertTrue(PreviewNotification.show(app, connectedState(42)))
            assertFalse(PreviewNotification.isDemoVisible(app))
            PreviewNotification.updateIfVisible(app, connectedState(50))
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.single()
                .notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("50%"))
        } finally {
            accountCache.edit().clear().commit()
            DemoMeter.exit(app)
            PreviewNotification.hide(app)
        }
    }

    @Test
    fun privacyPolicyIsAvailableInsideTheApp() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            button(controller.get(), "Privacy policy").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val text = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertTrue(text.contains("Try demo uses example values"))
            assertTrue(text.contains("Android Keystore"))
            assertTrue(text.contains("https://apolito53.github.io/codex-usage-tray-icon/privacy/"))
        }
    }

    @Test
    fun backgroundRefreshDoesNotCreateOrResurrectNotification() {
        val app = notificationContext()
        val state = connectedState(72)
        PreviewNotification.updateIfVisible(app, state)
        assertFalse(PreviewNotification.isVisible(app))

        assertTrue(PreviewNotification.show(app, state))
        // Simulate system dismissal, independently of the Hide action.
        app.getSystemService(NotificationManager::class.java).cancelAll()
        PreviewNotification.updateIfVisible(app, connectedState(60))
        assertFalse("A refresh must respect dismissal", PreviewNotification.isVisible(app))

        assertTrue("An explicit Show can restore the meter", PreviewNotification.show(app, state))
        HideNotificationReceiver().onReceive(app, Intent())
        PreviewNotification.updateIfVisible(app, connectedState(50))
        assertFalse("Hide must also survive later refreshes", PreviewNotification.isVisible(app))
    }

    @Test
    fun realReadingUpdatesOneSilentNotificationAndKeepsZeroDistinctFromUnknown() {
        val app = notificationContext()
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(PreviewNotification.show(app, connectedState(0)))
        val zero = manager.activeNotifications.single()
        assertTrue(zero.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("0% weekly remaining"))
        assertTrue(zero.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, zero.notification.visibility)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(PreviewNotification.CHANNEL_ID).importance)
        assertEquals(null, manager.getNotificationChannel(PreviewNotification.CHANNEL_ID).sound)

        PreviewNotification.updateIfVisible(app, connectedState(100).copy(stale = true))
        val updated = manager.activeNotifications.single()
        assertEquals("Reading updates must retain a single notification ID", zero.id, updated.id)
        val detail = updated.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(detail.contains("100% remaining"))
        assertTrue(detail.contains("STALE / OFFLINE"))
        assertTrue(detail.contains(UsageDisplay.reset(1_800_000_000)))
        assertFalse(detail.contains("sample", ignoreCase = true))

        PreviewNotification.updateIfVisible(app, connectedState(null))
        val unknown = manager.activeNotifications.single().notification
        assertTrue(unknown.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("unavailable"))
        assertFalse(unknown.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("0%"))
        PreviewNotification.hide(app)
    }

    @Test
    fun disconnectedStateRemovesPreviousReadingAndCannotBePosted() {
        val app = notificationContext()
        val connected = connectedState(42)
        assertTrue(PreviewNotification.show(app, connected))
        PreviewNotification.updateIfVisible(app, connected.copy(connected = false))
        assertFalse(PreviewNotification.isVisible(app))
        assertFalse(PreviewNotification.show(app, connected.copy(connected = false)))
    }

    @Test
    @Config(sdk = [34])
    fun deniedNotificationPermissionDoesNotPost() {
        val app = notificationContext()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(PreviewNotification.canPost(app))
        assertFalse(PreviewNotification.show(app, connectedState(72)))
        assertFalse(PreviewNotification.isVisible(app))
    }

    @Test
    fun missingResetDoesNotInventDate() {
        assertEquals("Reset time unavailable", UsageDisplay.reset(null))
        assertEquals("Reset time unavailable", UsageDisplay.reset(0))
        assertEquals("Reset time unavailable", UsageDisplay.reset(Long.MAX_VALUE))
        assertEquals("No successful weekly reading yet", UsageDisplay.observed(null))
    }

    private fun connectedState(remaining: Int?) = MeterState(
        connected = true,
        reading = UsageReading(remaining = remaining, resetsAtSeconds = 1_800_000_000, observedAtMillis = 1_799_000_000_000),
    )

    private fun notificationContext(): Application = RuntimeEnvironment.getApplication().also {
        shadowOf(it).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PreviewNotification.hide(it)
        PreviewNotification.createChannel(it)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun button(activity: MainActivity, label: String) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().first { it.text == label }

    private fun screenText(activity: MainActivity) =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().joinToString("\n") { it.text }

    private fun assertScreenReady(activity: MainActivity) {
        assertFalse("The meter should remain open after launch", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("The meter should display its controls", content.childCount > 0)
        val notifications = activity.getSystemService(NotificationManager::class.java)
        assertTrue("Opening the meter must not post a notification", notifications.activeNotifications.isEmpty())
    }
}
