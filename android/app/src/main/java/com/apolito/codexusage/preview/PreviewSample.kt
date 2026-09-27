package com.apolito.codexusage.preview

/** Deliberately contains no source, credentials, or live account state. */
enum class PreviewSample(val iconText: String, val label: String, val remaining: Int?) {
    ZERO("0", "0%", 0),
    NINE("9", "9%", 9),
    TEN("10", "10%", 10),
    SEVENTY_TWO("72", "72%", 72),
    NINETY_NINE("99", "99%", 99),
    HUNDRED("100", "100%", 100),
    UNKNOWN("?", "Unknown", null),
    ERROR("!", "Error", null);

    fun description(stale: Boolean): String = when {
        this == ERROR -> "Sample error · no reading available"
        this == UNKNOWN -> "Sample unknown · no reading available"
        stale -> "Sample $remaining% remaining · stale / offline"
        else -> "Sample $remaining% remaining · current fixture"
    }
}
