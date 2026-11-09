package com.apolito.codexusage.preview

import android.content.Context

/** A locally generated example. It never touches the account or its reading cache. */
internal object DemoMeter {
    private const val PREFS = "demo-meter"
    private const val THREE_DAYS_SECONDS = 3 * 24 * 60 * 60L

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isActive(context: Context) = preferences(context).getBoolean("active", false)

    fun start(context: Context, now: Long = System.currentTimeMillis()) {
        preferences(context).edit()
            .putBoolean("active", true)
            .putInt("remaining", 72)
            .putLong("reset", now / 1000 + THREE_DAYS_SECONDS)
            .putLong("observed", now)
            .apply()
    }

    fun reading(context: Context): UsageReading? {
        val prefs = preferences(context)
        if (!prefs.getBoolean("active", false)) return null
        return UsageReading(
            remaining = prefs.getInt("remaining", 72).takeIf { it in 0..100 } ?: 72,
            resetsAtSeconds = prefs.getLong("reset", 0).takeIf { it > 0 },
            observedAtMillis = prefs.getLong("observed", 0),
        )
    }

    /** Cycle example values to demonstrate refresh without requesting account data. */
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        val current = reading(context) ?: return
        val next = if (current.remaining == 72) 64 else 72
        preferences(context).edit().putInt("remaining", next).putLong("observed", now).apply()
    }

    fun exit(context: Context) = preferences(context).edit().clear().apply()
}
