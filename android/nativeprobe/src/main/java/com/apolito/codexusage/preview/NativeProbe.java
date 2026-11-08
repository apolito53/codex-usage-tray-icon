package com.apolito.codexusage.preview;

final class NativeProbe {
    static {
        System.loadLibrary("codex_android_native_probe");
    }

    private NativeProbe() {}

    static native String run();
}
