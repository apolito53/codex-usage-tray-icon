package com.apolito.codexusage.preview

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.TextView
import android.widget.Toast

/** Account credentials and sign-in stay in OpenAI's external browser flow. */
class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var icon: ImageView
    private lateinit var readingDescription: TextView
    private lateinit var readingDetails: TextView
    private lateinit var accountStatus: TextView
    private lateinit var accountMessage: TextView
    private lateinit var accountAction: Button
    private lateinit var challengeActions: LinearLayout
    private lateinit var loginActions: LinearLayout
    private lateinit var challengeCode: TextView
    private lateinit var checkLogin: Button
    private lateinit var refresh: Button
    private lateinit var signOut: Button
    private lateinit var showNotification: Button
    private lateinit var hideNotification: Button
    private lateinit var notificationStatus: TextView
    private var account = MeterState()
    private var stopObserving: (() -> Unit)? = null
    private var pendingShow = false
    private var notificationMessage: String? = null
    private var browserMessage: String? = null

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val bgColor get() = Color.parseColor(if (dark) "#15121C" else "#F8F5FC")
    private val textColor get() = Color.parseColor(if (dark) "#F4EFFB" else "#251B34")
    private val mutedColor get() = Color.parseColor(if (dark) "#C8BFD3" else "#62556F")
    private val surfaceColor get() = Color.parseColor(if (dark) "#26202F" else "#EEE7F6")
    private val accentColor get() = Color.parseColor(if (dark) "#C6AAFF" else "#6940AA")
    private val accentTextColor get() = Color.parseColor(if (dark) "#2B1549" else "#FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShow = savedInstanceState?.getBoolean("pendingShow") ?: false
        account = MeterAccount.state(this)
        PreviewNotification.createChannel(this)
        buildScreen()
        updateScreen()
        if (account.challenge != null) revealSignIn()
    }

    override fun onStart() {
        super.onStart()
        stopObserving = MeterAccount.observe(this) { state ->
            val newCode = state.challenge != null && account.challenge != state.challenge
            account = state
            if (state.challenge == null) browserMessage = null
            updateScreen()
            if (newCode) revealSignIn()
        }
    }

    override fun onStop() {
        stopObserving?.invoke()
        stopObserving = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingShow", pendingShow)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        MeterAccount.resumeLogin(this)
        account = MeterAccount.state(this)
        if (::notificationStatus.isInitialized) updateScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::notificationStatus.isInitialized) updateScreen()
    }

    private fun configureSystemBars() {
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
    }

    private fun buildScreen() {
        scroll = ScrollView(this).apply {
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
        column.addView(text("CODEX USAGE · ANDROID", 12f, accentColor, true))
        column.addView(text("A little number.\nA useful place.", 30f, textColor, true), rowParams(top = 12))
        column.addView(text("Your weekly Codex allowance, right in your phone’s status bar.", 16f, mutedColor), rowParams(top = 12))

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
        readingDescription = text("", 21f, textColor, true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(readingDescription, rowParams(top = 12))
        readingDetails = text("", 14f, mutedColor).apply { gravity = Gravity.CENTER }
        card.addView(readingDetails, rowParams(top = 8))

        accountStatus = text("", 17f, textColor, true)
        column.addView(accountStatus, rowParams(top = 24))
        accountMessage = text("", 14f, mutedColor).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(accountMessage, rowParams(top = 8))
        challengeActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(challengeActions, rowParams(top = 12))
        challengeActions.addView(text("ONE-TIME SIGN-IN CODE", 12f, accentColor, true))
        challengeCode = text("", 26f, textColor, true).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        challengeActions.addView(challengeCode, rowParams())
        challengeActions.addView(button("Copy code").apply {
            setOnClickListener {
                account.challenge?.let { challenge ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("OpenAI one-time sign-in code", challenge.userCode).apply {
                            description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                        },
                    )
                    Toast.makeText(this@MainActivity, "Sign-in code copied", Toast.LENGTH_SHORT).show()
                }
            }
        }, rowParams())
        accountAction = button("Sign in with ChatGPT", true).apply {
            setOnClickListener {
                browserMessage = null
                if (account.challenge != null) openSignIn() else MeterAccount.signIn(this@MainActivity)
            }
        }
        column.addView(accountAction, rowParams(top = 12))
        loginActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(loginActions, rowParams(top = 8))
        checkLogin = button("Check sign-in").apply {
            setOnClickListener {
                browserMessage = null
                MeterAccount.resumeLogin(this@MainActivity)
            }
        }
        loginActions.addView(checkLogin, LinearLayout.LayoutParams(0, -2, 1f))
        loginActions.addView(button("Cancel").apply {
            setOnClickListener {
                browserMessage = null
                MeterAccount.cancelLogin(this@MainActivity)
            }
        }, LinearLayout.LayoutParams(0, -2, 0.7f).apply { marginStart = dp(8) })

        val accountActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(accountActions, rowParams(top = 12))
        refresh = button("Refresh").apply {
            setOnClickListener { MeterAccount.refresh(this@MainActivity) }
        }
        accountActions.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        signOut = button("Sign out").apply {
            setOnClickListener {
                pendingShow = false
                notificationMessage = null
                PreviewNotification.hide(this@MainActivity)
                MeterAccount.signOut(this@MainActivity)
            }
        }
        accountActions.addView(signOut, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })

        column.addView(text("In your status bar", 17f, textColor, true), rowParams(top = 24))
        val notificationActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(notificationActions, rowParams(top = 10))
        showNotification = button("Show meter", true).apply { setOnClickListener { requestShow() } }
        notificationActions.addView(showNotification, LinearLayout.LayoutParams(0, -2, 1f))
        hideNotification = button("Hide").apply {
            setOnClickListener {
                pendingShow = false
                PreviewNotification.hide(this@MainActivity)
                notificationMessage = "Meter hidden. Tap Show meter to bring it back."
                updateScreen()
            }
        }
        notificationActions.addView(hideNotification, LinearLayout.LayoutParams(0, -2, 0.55f).apply { marginStart = dp(8) })
        notificationStatus = text("", 14f, mutedColor).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(notificationStatus, rowParams(top = 12))
        column.addView(button("Notification settings").apply { setOnClickListener { openNotificationSettings() } }, rowParams(top = 8))
        column.addView(text("The meter updates silently. If you hide or dismiss it, tap Show meter to restore it. Your phone may also hide silent status-bar icons in system settings.", 13f, mutedColor), rowParams(top = 12))
        column.addView(text("Sign-in and usage refresh run on this phone. Your computer does not need to be on.", 13f, mutedColor), rowParams(top = 18))
        setContentView(scroll)
        // PhoneWindow.getInsetsController() dereferences its decor view on
        // API 30+. Install the content/decor before asking for the controller.
        configureSystemBars()
        scroll.requestApplyInsets()
    }

    private fun revealSignIn() {
        scroll.post { if (account.challenge != null) scroll.smoothScrollTo(0, accountStatus.top - dp(12)) }
    }

    private fun openSignIn() {
        val challenge = account.challenge ?: return
        val uri = Uri.parse(challenge.verificationUrl)
        // The code belongs only in OpenAI's fixed device sign-in page. Never
        // launch a server-supplied arbitrary URL or an embedded credential view.
        if (uri.scheme != "https" || uri.host != "auth.openai.com" ||
            uri.path != "/codex/device" || uri.userInfo != null || uri.port != -1 ||
            uri.query != null || uri.fragment != null
        ) {
            browserMessage = "The OpenAI sign-in link is unavailable. Cancel and try signing in again."
            updateScreen()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            browserMessage = "Finish signing in in your browser, then return here."
        } catch (_: ActivityNotFoundException) {
            browserMessage = "No browser is available. Install or enable a browser, then open sign-in again."
        }
        updateScreen()
    }

    private fun requestShow() {
        notificationMessage = null
        account = MeterAccount.state(this)
        if (!account.connected) {
            notificationMessage = "Sign in with ChatGPT before showing your meter."
            updateScreen()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingShow = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        postMeter()
        updateScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && pendingShow) {
            pendingShow = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                postMeter()
            } else {
                notificationMessage = "Notification permission is off. Enable it in Notification settings, then tap Show meter."
            }
            updateScreen()
        }
    }

    private fun postMeter() {
        account = MeterAccount.state(this)
        if (!account.connected) {
            notificationMessage = "Sign in with ChatGPT before showing your meter."
            return
        }
        if (!PreviewNotification.canPost(this)) {
            notificationMessage = "Notifications or the meter channel are blocked. Open Notification settings, then tap Show meter."
            return
        }
        if (!PreviewNotification.show(this, account)) {
            notificationMessage = "Notification access changed. Check Notification settings, then tap Show meter."
            return
        }
        notificationMessage = null
        // NotificationManager enqueues the post; let the system add it to its
        // active list before reading the visibility back for the screen.
        notificationStatus.postDelayed({
            if (!isFinishing && !isDestroyed) updateScreen()
        }, 250)
    }

    private fun updateScreen() {
        val reading = account.reading.takeIf { account.connected }
        val remaining = reading?.remaining?.takeIf { it in 0..100 }
        val readingError = account.connected && reading == null && !account.busy &&
            (account.stale || account.message != null)
        icon.setImageBitmap(StatusIconRenderer.render(remaining, account.connected && account.stale, readingError))
        readingDescription.text = when {
            !account.connected -> "Your weekly allowance"
            remaining != null -> "$remaining% remaining"
            reading == null && account.busy -> "Checking weekly allowance…"
            else -> "Weekly allowance unavailable"
        }
        readingDetails.text = if (!account.connected) {
            "Connect with ChatGPT to see your real usage."
        } else {
            buildString {
                if (account.stale) {
                    append(if (reading == null) "OFFLINE · no weekly reading yet\n" else "STALE / OFFLINE · last known reading\n")
                }
                append(UsageDisplay.reset(reading?.resetsAtSeconds))
                append("\n")
                append(UsageDisplay.observed(reading?.observedAtMillis))
            }
        }
        accountStatus.text = when {
            account.connected -> "ChatGPT connected"
            account.challenge != null -> "Finish connecting in your browser"
            account.busy -> "Preparing sign-in…"
            else -> "Connect your ChatGPT account"
        }
        accountMessage.text = browserMessage ?: account.message ?: when {
            account.connected && account.busy -> "Refreshing your weekly allowance…"
            account.connected && account.stale && reading != null -> "Couldn’t refresh. The last reading stays visible until this phone can connect again."
            account.connected && account.stale -> "Couldn’t fetch your weekly allowance. Refresh when this phone can connect again."
            account.connected -> "Refresh here for the latest reading."
            account.challenge != null -> "Copy the one-time code, open OpenAI in your browser, and enter it there. Return here when you’re done."
            account.busy -> "Getting a one-time code from OpenAI…"
            else -> "Sign in on OpenAI’s website in your browser. Your password stays there."
        }
        challengeActions.visibility = if (account.challenge != null) View.VISIBLE else View.GONE
        loginActions.visibility = if (account.challenge != null) View.VISIBLE else View.GONE
        challengeCode.text = account.challenge?.userCode ?: ""
        accountAction.visibility = if (account.connected) View.GONE else View.VISIBLE
        accountAction.text = if (account.challenge != null) "Open ChatGPT sign-in" else "Sign in with ChatGPT"
        accountAction.isEnabled = account.challenge != null || !account.busy
        // A challenge remains usable during device-code polling.
        checkLogin.isEnabled = account.challenge != null
        refresh.visibility = if (account.connected) View.VISIBLE else View.GONE
        refresh.isEnabled = account.connected && !account.busy
        refresh.text = if (account.busy) "Refreshing…" else "Refresh"
        signOut.visibility = if (account.connected || account.canForget) View.VISIBLE else View.GONE
        signOut.text = if (account.canForget) "Forget saved sign-in" else "Sign out"
        showNotification.isEnabled = account.connected
        hideNotification.isEnabled = PreviewNotification.isVisible(this)
        notificationStatus.text = notificationMessage ?: when {
            !account.connected -> "Sign in, then tap Show meter. Nothing is posted automatically."
            !PreviewNotification.canPost(this) -> "Notifications are off. Show meter requests access, or use Notification settings."
            PreviewNotification.isVisible(this) -> "Meter is showing. Pull down to see the weekly reset time and reading status."
            else -> "Meter is hidden. Tap Show meter when you’re ready."
        }
    }

    private fun openNotificationSettings() {
        val intent = if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
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
