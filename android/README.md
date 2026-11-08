# Codex Usage Meter for Android

The meter shows the weekly Codex allowance remaining as a number in the status bar. Open the notification drawer for the last observation and local reset time. It runs on the phone and supports Android 8 or later on ARM64 and x86_64.

## Connect a ChatGPT account

1. Install the APK and open **Codex Usage Meter**. Version 0.2.0 updates the 0.1.1 preview when both are signed with the same Android key.
2. Tap **Sign in with ChatGPT**, copy the one-time code and tap **Open ChatGPT sign-in**. Sign in on OpenAI’s website and enter the code. If your account disables this flow, enable device code login in ChatGPT’s security settings. A workspace administrator controls this setting for managed accounts. [OpenAI device-code instructions](https://learn.chatgpt.com/docs/auth#login-on-headless-devices)
3. Return to the meter. After approval, it reads the allowance and reset time automatically. Tap **Show meter** to put the number in the status bar and grant notification permission if Android asks.

**Refresh** requests a reading immediately. Android also schedules a network-connected refresh roughly every 15 minutes, subject to power and network conditions. A failed refresh preserves the previous reading and marks it stale. A successful response with no weekly window shows an unknown value. **Hide** dismisses the indicator. **Sign out** clears the phone’s session and cached reading and stops its periodic job.

## How it works

The APK embeds the open-source Codex account libraries at the revision in [upstream-revision.txt](native/upstream-revision.txt). It runs Codex’s device-code login, renewable session and passive allowance reader. The Kotlin transport uses Android’s standard HTTPS certificate and hostname verification for the device authorization, token and allowance requests. It rejects redirects and restricts each destination to its expected method and path. The app identifies itself as `codex_usage_meter_android`. [OpenAI Codex account methods](https://learn.chatgpt.com/docs/app-server#auth-endpoints)

The upstream library holds session data in memory. The only persistent credential record is encrypted with an Android Keystore AES-GCM key and written atomically to app-private, backup-excluded storage. A separate cache holds the latest allowance, timestamps and a digest used to match the reading to the signed-in account. The app’s diagnostics contain operation outcomes only.

## Build

Use the [Android native library workflow](../.github/workflows/android-native.yml) to build the two libraries from the intended source revision. Download `account-lib-arm64-v8a` and `account-lib-x86_64`. Extract their ABI directories into `android/native/out`. Extract the `notices` directory from the [dependency notices workflow](../.github/workflows/android-notices.yml) into the same location. This produces:

```text
android/native/out/arm64-v8a/libcodex_android_native_probe.so
android/native/out/x86_64/libcodex_android_native_probe.so
android/native/out/notices/native-notices.zip
android/native/out/notices/notice-report.json
```

The library filename is shared with the original experiment. The build script checks the production JNI exports before publishing a library artifact. The separate `:nativeprobe` APK remains an offline diagnostic.

To build the libraries on Linux directly, install Rust 1.95.0 with both Android targets, the Android NDK 28.2.13676358 and the host build tools for the pinned crates. With `ANDROID_HOME` set, run the following from the repository root:

```sh
bash android/build-native.sh aarch64-linux-android "$PWD/android/native/out"
bash android/build-native.sh x86_64-linux-android "$PWD/android/native/out"
```

Build the app from `android/` with JDK 17, Android SDK 36 and the checked-in Gradle wrapper. Set `-PaccountNativeDir=/absolute/path` if the libraries and notices are elsewhere.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. For a non-debuggable sideload build, run `:app:assembleRelease`, sign the unsigned APK locally with the same key as the prior install, and verify it with `apksigner verify --verbose`. The maintainers’ signing key is kept on the signing machine.

## Device checks and diagnostics

Run the synthetic native-account instrumentation suite on a disposable x86_64 emulator. It uses the packaged Rust library, a test-only transport and Android Keystore. The emulator must have a clean test account store. The remaining live account check uses the OpenAI page opened by the app on the owner’s phone.

```sh
./gradlew :app:connectedDebugAndroidTest
```

For a process-restart check, run the dedicated instrumentation test twice on the
same clean emulator. The first invocation writes synthetic credentials. The
second requires the matching fixture and a new process, then clears it.

```sh
test_class=com.apolito.codexusage.preview.NativeAccountProcessRestartTest
runner=com.apolito.codexusage.preview.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class "$test_class" -e accountProcessPhase prepare "$runner"
adb shell am force-stop com.apolito.codexusage.preview
adb shell am instrument -w -e class "$test_class" -e accountProcessPhase restore "$runner"
```

Android’s `logd` service collects the app’s bounded lifecycle and refresh events. Use a trusted attached phone or disposable emulator, select it with `adb -s SERIAL`, and run `logcat -d -s CodexUsageMeter:I` after sign-in or refresh. The platform keeps a rolling diagnostic buffer. Android runs the app normally if an ADB client is absent.

The [September notification and account spike](../docs/android-phase-0.md) records the original sample APK, native lab and early device checks.
