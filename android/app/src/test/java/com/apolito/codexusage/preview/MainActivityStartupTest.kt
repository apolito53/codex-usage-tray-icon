package com.apolito.codexusage.preview

import android.app.NotificationManager
import android.view.ViewGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    private fun assertScreenReady(activity: MainActivity) {
        assertFalse("The preview should remain open after launch", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("The preview should display its controls", content.childCount > 0)
        val notifications = activity.getSystemService(NotificationManager::class.java)
        assertTrue("Opening the preview must not post a notification", notifications.activeNotifications.isEmpty())
    }
}
