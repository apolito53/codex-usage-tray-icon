package com.apolito.codexusage.preview

/** Production account adapter. Every blocking call runs on an application worker. */
object NativeAccount {
    init { System.loadLibrary("codex_android_native_probe") }

    @JvmStatic external fun initialize(platform: AccountPlatform, home: String): String
    @JvmStatic external fun startLogin(): String
    @JvmStatic external fun finishLogin(attempt: Long): String
    @JvmStatic external fun cancelLogin(attempt: Long): String
    @JvmStatic external fun readUsage(): String
    @JvmStatic external fun signOut(): String
}
