package com.apolito.codexusage.preview

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import java.text.DateFormat
import java.util.Date

/** A single silent notification. Only an explicit Show action creates it. */
object PreviewNotification {
    // Retain the preview channel and ID so upgrades preserve Android settings
    // and replace a previous notification rather than creating a second one.
    const val CHANNEL_ID = "usage_preview_v1"
    private const val NOTIFICATION_ID = 117
    private const val DEMO_EXTRA = "com.apolito.codexusage.DEMO"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager(context).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun isVisible(context: Context): Boolean =
        manager(context).activeNotifications.any { it.id == NOTIFICATION_ID }

    fun isDemoVisible(context: Context): Boolean =
        manager(context).activeNotifications.any {
            it.id == NOTIFICATION_ID && it.notification.extras.getBoolean(DEMO_EXTRA, false)
        }

    /** Called only in response to the user's explicit Show action. */
    @Synchronized
    fun show(context: Context, state: MeterState): Boolean {
        if (!state.connected) return false
        createChannel(context)
        return canPost(context) && post(context, state, demo = false)
    }

    /** A demo notification is always explicitly labeled and never requires a session. */
    @Synchronized
    fun showDemo(context: Context, reading: UsageReading): Boolean {
        createChannel(context)
        return canPost(context) && post(context, MeterState(connected = true, reading = reading), demo = true)
    }

    @Synchronized
    fun updateDemoIfVisible(context: Context, reading: UsageReading) {
        if (isDemoVisible(context) && canPost(context)) {
            post(context, MeterState(connected = true, reading = reading), demo = true)
        }
    }

    /** Safe for the controller's background refresh; never resurrect a dismissal. */
    @Synchronized
    fun updateIfVisible(context: Context, state: MeterState) {
        // The account controller must not replace or dismiss an independent demo.
        if (isDemoVisible(context)) return
        if (!state.connected) {
            hide(context)
        } else if (isVisible(context) && canPost(context)) {
            post(context, state, demo = false)
        }
    }

    private fun post(context: Context, state: MeterState, demo: Boolean): Boolean {
        val reading = state.reading
        val remaining = reading?.remaining?.takeIf { it in 0..100 }
        val readingError = reading == null && !state.busy && (state.stale || state.message != null)
        val openMeter = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, HideNotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (demo && remaining != null) {
            "Demo · $remaining% weekly remaining"
        } else if (demo) {
            "Demo · weekly allowance unavailable"
        } else if (remaining != null) {
            "Allowance Meter · $remaining% weekly remaining"
        } else {
            "Allowance Meter · weekly allowance unavailable"
        }
        val status = buildString {
            if (demo) append("EXAMPLE DATA · ")
            if (state.stale) append("STALE / OFFLINE · ")
            append(UsageDisplay.observed(reading?.observedAtMillis))
        }
        val detail = buildString {
            if (demo) append("DEMO · EXAMPLE DATA — not your account\n")
            if (state.stale) {
                append(if (reading == null) "OFFLINE — no weekly reading yet\n" else "STALE / OFFLINE — last known reading\n")
            }
            if (remaining != null) {
                append("Weekly allowance: $remaining% remaining\n")
            } else {
                append("Weekly allowance unavailable\n")
            }
            if (demo) append("Example ")
            append(UsageDisplay.reset(reading?.resetsAtSeconds).replaceFirstChar { if (demo) it.lowercase() else it.toString() })
            append("\n")
            append(UsageDisplay.observed(reading?.observedAtMillis))
            append("\nTap to open your meter.")
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(Icon.createWithBitmap(StatusIconRenderer.render(remaining, state.stale, readingError)))
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .addExtras(Bundle().apply { putBoolean(DEMO_EXTRA, demo) })
            .setContentIntent(openMeter)
            // Android 14+ can dismiss ongoing notifications. Reuse the existing
            // receiver to also cancel an update racing the dismissal broadcast.
            .setDeleteIntent(hide)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hide meter", hide).build())
            .build()
        return try {
            manager(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Permission may change between canPost() and the system enqueue.
            false
        }
    }

    @Synchronized
    fun hide(context: Context) = manager(context).cancel(NOTIFICATION_ID)
}

class HideNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PreviewNotification.hide(context)
    }
}

/** Shared, local-time text for real readings. Missing timestamps stay missing. */
internal object UsageDisplay {
    fun reset(seconds: Long?): String =
        if (seconds != null && seconds > 0 && seconds <= Long.MAX_VALUE / 1000) {
            "Resets ${dateTime(seconds * 1000)}"
        } else {
            "Reset time unavailable"
        }

    fun observed(millis: Long?): String =
        if (millis != null && millis > 0) "Updated ${dateTime(millis)}" else "No successful weekly reading yet"

    private fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
