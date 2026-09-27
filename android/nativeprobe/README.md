# Offline Android native diagnostic

This separate developer APK exercises the pinned native account libraries with
synthetic inputs and an unpublished network policy. It has no permissions and
opens no real account credentials. Its separate storage check uses only fixed
fixtures in an isolated Keystore namespace. It is an internal validation tool,
not a live usage meter or a sign-in screen.

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

Version 0.1.1-lab adds **Synthetic storage checks**. Save, Read, Overwrite, and
Delete exercise the actual upstream auth-storage methods through the custom
Keystore adapter. Force-stop and cold-launch between operations to check disk
persistence; the UI reports `initial`, `overwritten`, or `absent`, never tokens.
The vault self-check uses a separate synthetic namespace. The native failure
selector deliberately affects the primary synthetic fixture, so use those
controls after the persistence sequence and finish with `fault-clear`.

Native fault checks should expose error 1 for injected unavailability, error 4
for an injected pre-rename write failure, error 2 for tampered ciphertext, and
error 3 for a removed encryption key. A failed injected write must leave the
previous record readable. Missing keys must not be silently regenerated over
existing records. Fault setup success is distinct from a successful storage
operation; expected failures must still display **STORAGE CHECK FAILED**.

Android 11/API 30 x86_64 installation, cold launch, native loading, and this
synthetic result passed. The app's crash buffer was empty. ARM64 compilation
and linking passed; physical ARM64 execution has not been tested. Synthetic
storage persistence also passed separate save/overwrite/delete sequences across
app restarts. No TLS, live login, refresh, or quota-retrieval success is implied.

Before distributing a native APK, finish the dependency-notice review described
in [the collector instructions](../native/licenses/README.md) and include its
reviewed bundle in APK assets. The internal runtime-test APK is not an external
release. Its unoptimized size does not estimate the eventual production build.
