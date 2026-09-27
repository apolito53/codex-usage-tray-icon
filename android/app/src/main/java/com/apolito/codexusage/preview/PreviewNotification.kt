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

object PreviewNotification {
    const val CHANNEL_ID = "usage_preview_v1"
    private const val NOTIFICATION_ID = 117

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
        manager(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun isVisible(context: Context): Boolean =
        manager(context).activeNotifications.any { it.id == NOTIFICATION_ID }

    fun show(context: Context, sample: PreviewSample, stale: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val openPreview = PendingIntent.getActivity(
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
        val title = when (sample) {
            PreviewSample.UNKNOWN -> "PREVIEW · unknown (sample data)"
            PreviewSample.ERROR -> "PREVIEW · error (sample data)"
            else -> "PREVIEW · ${sample.remaining}% remaining (sample)"
        }
        val state = when {
            sample == PreviewSample.ERROR -> "Sample fetch failed; no previous reading."
            sample == PreviewSample.UNKNOWN -> "Sample account has no known weekly reading."
            stale -> "STALE / OFFLINE · sample last update: 45 min ago."
            else -> "Sample updated just now."
        }
        val detail = buildString {
            append("SAMPLE DATA ONLY — no account connected.\n")
            append(state)
            if (sample.remaining != null) {
                append("\nWeekly: ${sample.remaining}% remaining (sample)")
                append("\nResets Thursday at 8:00 PM (sample)")
                append("\n5-hour window: 84% remaining (sample)")
            }
            if (stale && sample.remaining == null) append("\nSTALE / OFFLINE preview badge enabled.")
            append("\nTap to return to this preview.")
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(Icon.createWithBitmap(StatusIconRenderer.render(sample, stale)))
            .setContentTitle(title)
            .setContentText(state)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openPreview)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hide preview", hide).build())
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) = manager(context).cancel(NOTIFICATION_ID)
}

class HideNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PreviewNotification.hide(context)
    }
}
