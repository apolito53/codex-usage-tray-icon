package com.apolito.codexusage.preview

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
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
            assertFalse("Credentials belong in the browser", views.any { it is EditText })
            assertFalse("Fixtures must not become genuine readings", views.any {
                it is TextView && (it.text.contains("72%") || it.text.contains("Choose a sample"))
            })
            val show = views.filterIsInstance<Button>().first { it.text == "Show meter" }
            assertFalse("Sign-in is required before showing a meter", show.isEnabled)
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

    private fun assertScreenReady(activity: MainActivity) {
        assertFalse("The meter should remain open after launch", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("The meter should display its controls", content.childCount > 0)
        val notifications = activity.getSystemService(NotificationManager::class.java)
        assertTrue("Opening the meter must not post a notification", notifications.activeNotifications.isEmpty())
    }
}
