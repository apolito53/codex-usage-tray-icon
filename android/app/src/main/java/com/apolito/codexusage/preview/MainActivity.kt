package com.apolito.codexusage.preview

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** A disposable readability spike. This activity never contacts a usage source. */
class MainActivity : Activity() {
    private lateinit var icon: ImageView
    private lateinit var sampleDescription: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var staleSwitch: Switch
    private val sampleButtons = mutableMapOf<PreviewSample, Button>()
    private var sample = PreviewSample.SEVENTY_TWO
    private var stale = false
    private var pendingShow = false
    private var statusMessage: String? = null

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val bgColor get() = Color.parseColor(if (dark) "#15121C" else "#F8F5FC")
    private val textColor get() = Color.parseColor(if (dark) "#F4EFFB" else "#251B34")
    private val mutedColor get() = Color.parseColor(if (dark) "#C8BFD3" else "#62556F")
    private val surfaceColor get() = Color.parseColor(if (dark) "#26202F" else "#EEE7F6")
    private val accentColor get() = Color.parseColor(if (dark) "#C6AAFF" else "#6940AA")
    private val accentTextColor get() = Color.parseColor(if (dark) "#2B1549" else "#FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getPreferences(MODE_PRIVATE)
        sample = PreviewSample.entries.firstOrNull { it.name == preferences.getString("sample", null) }
            ?: PreviewSample.SEVENTY_TWO
        stale = preferences.getBoolean("stale", false)
        pendingShow = savedInstanceState?.getBoolean("pendingShow") ?: false
        PreviewNotification.createChannel(this)
        buildScreen()
        updateScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingShow", pendingShow)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::notificationStatus.isInitialized) {
            statusMessage = null
            updateScreen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::notificationStatus.isInitialized) updateScreen()
    }

    private fun buildScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val appearance = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            clipToPadding = false
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        column.addView(text("CODEX USAGE · ANDROID PREVIEW", 12f, accentColor, true))
        column.addView(text("A little number.\nA useful place.", 30f, textColor, true), rowParams(top = 12))
        column.addView(text("See how the desktop-style percentage reads in your phone’s status bar. Everything here is sample data.", 16f, mutedColor), rowParams(top = 12))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(surfaceColor)
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        column.addView(card, rowParams(top = 24))
        icon = ImageView(this).apply {
            imageTintList = ColorStateList.valueOf(textColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(80), dp(80)))
        sampleDescription = text("", 16f, textColor, true).apply { gravity = Gravity.CENTER }
        card.addView(sampleDescription, rowParams(top = 12))
        card.addView(text("Enlarged preview · Android controls the actual icon size and tint", 12f, mutedColor).apply { gravity = Gravity.CENTER }, rowParams(top = 6))

        column.addView(text("Choose a sample", 16f, textColor, true), rowParams(top = 24))
        PreviewSample.entries.chunked(4).forEach { samples ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            column.addView(row, rowParams(top = 6))
            samples.forEachIndexed { index, value ->
                val button = button(value.label).apply {
                    textSize = 13f
                    setPadding(dp(2), 0, dp(2), 0)
                    contentDescription = if (value.remaining != null) "Sample ${value.remaining} percent remaining" else "Sample ${value.label}"
                    setOnClickListener {
                        sample = value
                        sampleChanged()
                    }
                }
                sampleButtons[value] = button
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
        }
        staleSwitch = Switch(this).apply {
            text = "Stale / offline sample"
            textSize = 16f
            setTextColor(textColor)
            minHeight = dp(56)
            isChecked = stale
            setOnCheckedChangeListener { _, checked ->
                stale = checked
                sampleChanged()
            }
        }
        column.addView(staleSwitch, rowParams(top = 10))
        column.addView(text("Stale keeps the last sample number and adds a monochrome × badge. Unknown (?) and error (!) never pretend to be 0%.", 13f, mutedColor), rowParams(top = 2))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(actions, rowParams(top = 22))
        actions.addView(button("Show preview", true).apply { setOnClickListener { requestShow() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Hide").apply {
            setOnClickListener {
                PreviewNotification.hide(this@MainActivity)
                statusMessage = "Preview hidden."
                updateScreen()
            }
        }, LinearLayout.LayoutParams(0, -2, 0.55f).apply { marginStart = dp(8) })

        notificationStatus = text("", 14f, mutedColor).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(notificationStatus, rowParams(top = 12))
        column.addView(button("Notification settings").apply { setOnClickListener { openNotificationSettings() } }, rowParams(top = 8))
        column.addView(text("If the number is missing, your phone may hide silent notification icons or limit how many fit. Check the notification settings and your system’s status-bar settings. Newer Android versions may also let you dismiss an ongoing notification; Show preview restores it.", 13f, mutedColor), rowParams(top = 12))
        column.addView(text("This preview does not connect to your account or show real usage. Tap its notification to return to the sample picker.", 13f, mutedColor), rowParams(top = 18))
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun sampleChanged() {
        getPreferences(MODE_PRIVATE).edit().putString("sample", sample.name).putBoolean("stale", stale).apply()
        statusMessage = null
        if (PreviewNotification.isVisible(this) && PreviewNotification.canPost(this)) {
            postPreview()
        }
        updateScreen()
    }

    private fun requestShow() {
        statusMessage = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingShow = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        postPreview()
        updateScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && pendingShow) {
            pendingShow = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                postPreview()
            } else {
                statusMessage = "Notification permission is off. Enable it in Notification settings, then tap Show preview."
            }
            updateScreen()
        }
    }

    private fun postPreview() {
        if (!PreviewNotification.canPost(this)) {
            statusMessage = "Notifications or this preview channel are blocked. Open Notification settings to enable them, then tap Show preview."
            return
        }
        try {
            PreviewNotification.show(this, sample, stale)
            // NotificationManager enqueues the post; query again after the
            // system has had a moment to add it to activeNotifications.
            notificationStatus.postDelayed({
                if (!isFinishing && !isDestroyed) updateScreen()
            }, 250)
        } catch (_: SecurityException) {
            statusMessage = "Notification access changed. Check Notification settings, then tap Show preview."
        }
    }

    private fun updateScreen() {
        icon.setImageBitmap(StatusIconRenderer.render(sample, stale))
        sampleDescription.text = sample.description(stale)
        sampleButtons.forEach { (value, button) ->
            val selected = sample == value
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) accentColor else surfaceColor)
            button.setTextColor(if (selected) accentTextColor else textColor)
        }
        notificationStatus.text = statusMessage ?: when {
            !PreviewNotification.canPost(this) -> "Notifications are off. Show preview requests access, or use Notification settings."
            PreviewNotification.isVisible(this) -> "Preview is showing. Pull down to inspect it; selecting another sample updates the same notification."
            else -> "Preview is hidden. Tap Show preview when you’re ready."
        }
    }

    private fun openNotificationSettings() {
        val intent = if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, PreviewNotification.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(value: String, primary: Boolean = false) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        backgroundTintList = ColorStateList.valueOf(if (primary) accentColor else surfaceColor)
        setTextColor(if (primary) accentTextColor else textColor)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun rowParams(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
