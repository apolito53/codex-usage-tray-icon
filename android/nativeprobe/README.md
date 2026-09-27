# Offline Android native diagnostic

This separate developer APK exercises the pinned native account libraries with
synthetic inputs and an unpublished network policy. It has no permissions and
never opens credential storage. It is an internal validation tool, not a live
usage meter or a sign-in screen.

Build the native library first using [the native instructions](../native/README.md).
From the `android` directory, package one or both supported ABIs:

```bash
PROBE_TARGET=aarch64-linux-android PROBE_ACTION=build bash native/check-android.sh
mkdir -p native/jniLibs/arm64-v8a
cp native/target/aarch64-linux-android/debug/libcodex_android_native_probe.so \
  native/jniLibs/arm64-v8a/

# Add this ABI for an x86_64 emulator.
PROBE_TARGET=x86_64-linux-android PROBE_ACTION=build bash native/check-android.sh
mkdir -p native/jniLibs/x86_64
cp native/target/x86_64-linux-android/debug/libcodex_android_native_probe.so \
  native/jniLibs/x86_64/

./gradlew :nativeprobe:assembleDebug :nativeprobe:lintDebug \
  -PnativeProbeLibDir="$(pwd)/native/jniLibs"
adb install -r nativeprobe/build/outputs/apk/debug/nativeprobe-debug.apk
adb shell am start -n \
  com.apolito.codexusage.nativeprobe/com.apolito.codexusage.preview.NativeProbeActivity
```

The copy paths assume the native build's default target directory; adjust them
if setting `CARGO_TARGET_DIR`. This app uses a different package from the working
notification preview, so they can coexist. The Gradle build checks ABI-folder
placement and fails if neither supported ABI contains the library.

The screen must say **OFFLINE CHECK PASSED**. The report must contain the pinned
commit, synthetic expiration `1700000000`, both upstream unavailable-policy
errors, `credential_store_opened: false`, and `transport_attempted: false`.
Failure or native-load errors are displayed on screen. Panics are caught at the
JNI boundary when Rust unwinding is available; a process abort cannot be caught.

Android 11/API 30 x86_64 installation, cold launch, native loading, and this
synthetic result passed. The app's crash buffer was empty. ARM64 compilation
and linking passed; physical ARM64 execution has not been tested. No TLS,
Keystore persistence, live login, refresh, or quota-retrieval success is implied.

Before distributing a native APK, finish the dependency-notice review described
in [the collector instructions](../native/licenses/README.md) and include its
reviewed bundle in APK assets. The internal runtime-test APK is not an external
release. Its unoptimized size does not estimate the eventual production build.
