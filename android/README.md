# Allowance Meter for Android

The meter shows the weekly Codex allowance remaining as a number in the status bar. Open the notification drawer for the last observation and local reset time. It runs on the phone and supports Android 8 or later on ARM64 and x86_64.

## Connect a ChatGPT account

1. Open **Allowance Meter** (**Codex Usage Meter** on the 0.2.1 sideload). The 0.3.0 Play edition uses its own package and can sit beside the sideload. Sign in separately on the Play edition; choose **Hide** in the old app when you're ready to switch.
2. Tap **Sign in with ChatGPT**, copy the one-time code and tap **Open ChatGPT sign-in**. Sign in on OpenAI’s website and enter the code. If your account disables this flow, enable device code login in ChatGPT’s security settings. A workspace administrator controls this setting for managed accounts. [OpenAI device-code instructions](https://learn.chatgpt.com/docs/auth#login-on-headless-devices)
3. Return to the meter. After approval, it reads the allowance and reset time automatically. Tap **Show meter** to put the number in the status bar and grant notification permission if Android asks.

**Try demo** shows clearly labeled example readings without an account. **Refresh** changes the sample and **Exit demo** removes it. With a real account, **Refresh** reads your allowance immediately. Android also schedules refreshes roughly every 15 minutes, subject to power and network conditions. A failed refresh preserves the last reading and marks it stale. **Hide** dismisses the indicator. **Sign out** clears the reading, stops refreshes and attempts to remove the saved session. See the [privacy policy](https://apolito53.github.io/codex-usage-tray-icon/privacy/) for details.

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

The standard APK uses the permanent application ID `com.apolito.codexusage.meter`. Keep that ID and signing key for updates. To update a working 0.2.0 install in place and retain its session, pass `-PupdatePreviewPackage=true` and sign with that install's key. This selects its original ID, `com.apolito.codexusage.preview`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. For a non-debuggable sideload build, run `:app:assembleRelease`, sign the unsigned APK locally, and verify it with `apksigner verify --verbose`. Run `python verify-native-pages.py path/to/signed.apk` to check the packaged ELF load segments for 16 KB page compatibility. The maintainers’ signing key is kept on the signing machine.

## Google Play build

The Play App Bundle uses its own package, `com.apolito.allowancemeter` (`-PplayDistribution=true`). The pre-Play sideload uses a debug certificate; do not import it as a Play signing key. The private sideload and Play installations have independent settings, sessions, and notifications. Play users can update normally after the initial install.

On the Windows signing machine, set `JAVA_HOME` and `ANDROID_HOME` as above. Download the [official Google bundletool JAR](https://github.com/google/bundletool/releases/tag/1.18.3). The tested JAR's SHA-256 is `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`. Fetch the current native binaries and **reviewed** notice bundle as described above.

```powershell
# First-ever setup only, if there is no existing upload key:
.\initialize-play-upload-key.ps1
# Every release (from android/):
.\build-play.ps1 -Bundletool C:\tools\bundletool-all-1.18.3.jar
```

The script signs with the owner’s dedicated upload key and verifies both native ABIs, the packaged attribution review, 16 KB configuration, and the JAR signature. By default the AAB is in `app/build/outputs/play`. Use `-OutputDirectory` for a new destination; an existing release file is never overwritten. Google Play App Signing should generate and hold the **app signing key** for the first store release. The local **upload key** only authenticates future uploads.

The upload key, public certificate, and Windows DPAPI-protected password live outside the repository under `%LOCALAPPDATA%\AllowanceMeter\PlaySigning`. Only the PEM certificate is public. Keep the keystore and its password in an owner-managed secure backup; the DPAPI file alone is not a portable backup. If this machine is lost, Play also supports an [upload-key reset](https://developer.android.com/studio/publish/app-signing#reset_upload_key).

See the [Play listing, artwork, screenshots and app-access worksheet](play/README.md). A local build does not create a listing or start a Play test.

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
runner=com.apolito.codexusage.meter.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class "$test_class" -e accountProcessPhase prepare "$runner"
adb shell am force-stop com.apolito.codexusage.meter
adb shell am instrument -w -e class "$test_class" -e accountProcessPhase restore "$runner"
```

Android’s `logd` service collects the app’s bounded lifecycle and refresh events. Use a trusted attached phone or disposable emulator, select it with `adb -s SERIAL`, and run `logcat -d -s CodexUsageMeter:I` after sign-in or refresh. The platform keeps a rolling diagnostic buffer. Android runs the app normally if an ADB client is absent.

The [September notification and account spike](../docs/android-phase-0.md) records the original sample APK, native lab and early device checks.
