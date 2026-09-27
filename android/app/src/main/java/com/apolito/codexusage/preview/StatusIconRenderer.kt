package com.apolito.codexusage.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface

/**
 * Notification small icons use their alpha mask: Android supplies the tint.
 * Match the desktop's circle and centered number, leaving the background clear.
 */
object StatusIconRenderer {
    fun render(sample: PreviewSample, stale: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(32f, 32f, 29.5f, paint)

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = when (sample.iconText.length) {
            3 -> 25f
            2 -> 38f
            else -> 42f
        }
        val metrics = paint.fontMetrics
        val baseline = 32f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(sample.iconText, 32f, baseline, paint)

        if (stale) {
            // A punched-out X in a bright disk survives monochrome system tint.
            // Clear the overlap first so the circle/number cannot obscure it.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawCircle(53f, 11f, 11f, paint)
            paint.xfermode = null
            canvas.drawCircle(53f, 11f, 9f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            paint.strokeWidth = 2.5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(50f, 8f, 56f, 14f, paint)
            canvas.drawLine(50f, 14f, 56f, 8f, paint)
            paint.xfermode = null
        }
        return bitmap
    }
}
