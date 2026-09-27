# Android meter: phase 0

September 27, 2026. Source/authentication findings and a notification-readability
preview. This work does not decide whether later capabilities share an app or
architecture.

## Result so far

The independent notification-readability preview lives in `android/`. It uses
sample values only. Live quota integration remains gated on a viable data/login
route. This is not a working account meter yet.

The first implementation spike uses Kotlin with platform Views instead of
Compose, and a dynamic alpha-only bitmap instead of generated resources. This
keeps the disposable test small while using the actual Android notification
pipeline. Neither choice locks the final app's UI architecture. The pinned
toolchain is AGP 8.10.1, Kotlin 2.1.20, Gradle 8.11.1, JDK 17, compile/target SDK
36, and minimum SDK 26. The production target/toolchain will be revisited before
the real app ships.

## Gate A: source and authentication

**Disposition: native execution and synthetic persistence are verified;
live standalone access is not yet established.** The official custom-client
integration through `codex app-server` documents managed browser/device-code
sign-in and quota reads. Its managed flow has no separate client-registration
step. Reusing the real account components on Android remains an integration
experiment: service acceptance, TLS trust, and the complete phone-owned session
lifecycle have not been verified.

The documented external-token mode assumes a host already owns the user's
authentication lifecycle. Platform API keys do not substitute for a ChatGPT
subscription session. The access-token documentation currently describes
Business/Enterprise automation and does not establish this personal Android
use case. No login was initiated, no real account auth file was read, and no
account token or cookie was copied during the investigation.

The browser destination was extracted from the official pricing page:

https://chatgpt.com/codex/settings/usage

Native Android routing to that exact page remains untested. The preview's
sample notification opens its own screen, so it cannot imply the sample quota
came from the account dashboard.

The user subsequently confirmed that phone independence is a requirement.
A desktop companion may be explored as a separate optional route; it is not
selected as the Android meter's source and does not satisfy that requirement.
Live integration still needs an independently owned mobile session.

A deeper source review found a concrete porting candidate in upstream commit
`8f195c93d7e7acfef95acf273f0e49cce917e291`: `codex-login` exposes the managed
device-code flow, and `codex-backend-client` exposes quota reads. Neither crate
directly depends on the full `codex-core` agent engine. These provided a native
Android library candidate. The NDK builds now pass; their graph is
broad and the unoptimized APK is large. Android's default backend in the pinned
keyring crate is a mock, so the diagnostic installs an explicit Keystore
adapter. Source reuse alone does not establish service acceptance or a stable
hosted quota API.

The pinned JNI experiment has built both ARM64 and x86_64 libraries and passed
Android x86_64 loading, default-deny request checks, and synthetic Keystore
persistence. TLS/platform compatibility and the real session lifecycle remain
the next gates. No desktop runtime, Termux dependency, or first-party credential
extraction has been added, and no live authorization has been initiated.

If explored separately, a desktop companion would keep Codex credentials on
the host and expose only normalized quota/reset metadata through authenticated
transport. It would depend on that host being online; a cached relay response
must retain the host's original observation time. No host service, relay,
remote RPC exposure, or new hosting has been added.

## Gate B: notification readability

The preview lets the user explicitly show a numeric sample, unknown, error,
and stale states. Its notification is labeled as sample data and uses one
silent LOW-importance channel. It has no network permission, credential
handling, scheduled fetch, boot receiver, or foreground service. The preview
posts through Android's actual small-icon API. The user confirmed successful
phone launch and gave positive feedback; the full notification-readability
matrix is still pending. The enlarged icon inside the app does not establish
status-bar legibility.

The user should inspect one-, two-, and three-digit samples in their real
status bar, plus the stale marker, in light and dark System UI. The device's
notification permission/channel and "hide silent icons" settings control
visibility. A modern Android version can dismiss the ongoing notification;
the preview does not repost it automatically.

Build/lint verification establishes packaging and static correctness only.
Physical-phone legibility and native link routing remain user-device checks.

## Initial build verification (0.1.0)

`./gradlew :app:assembleDebug :app:lintDebug` succeeded with the pinned JDK/SDK.
Lint reports zero errors and two nonblocking warnings: an English-only sample
label and the newer backup-extraction configuration recommendation. Only the
fixture selection is persisted, and cloud backup is disabled. Device-transfer
backup rules need revisiting before any account data is introduced.

`apksigner verify --verbose` verified the debug APK's v2 signature. Inspection
of the packaged manifest confirmed the only requested permission is
`POST_NOTIFICATIONS`, with package `com.apolito.codexusage.preview`, minimum SDK
26, target SDK 36, and version `0.1.0-preview`. No emulator or physical-device
execution was available for the initial delivery. There was no claim of runtime or
readability acceptance yet.

Delivered preview: `usage-meter-preview-0.1.0.apk` (849,847 bytes), debug-signed
for this disposable preview, not a production release. Its SHA-256 is:

```text
a7b4c7662b819bd2d40df2c9c85c3ac85be017052520169796cf08a78565a9da
```

This initial APK was reported to crash on launch and is superseded by 0.1.1.
The startup code requested `Window.insetsController` before installing a
decor view. Android's `PhoneWindow` dereferences that view internally, before
Kotlin's nullable-controller check can run. Version 0.1.1 moves system-bar
configuration after `setContentView` and increments the version code to 2.

## Startup repair verification (0.1.1)

The replacement builds and passes Android lint. The dedicated Robolectric
test exercises cold launch, screen recreation, a populated content view, and
no notification on launch against API 30 and 34 (two tests, zero failures).
Restoring the original initialization order in an isolated copy makes both
tests fail with `PhoneWindow.getInsetsController()` reporting a null `mDecor`.
The production source was not changed during that negative check.

These are JVM Android-framework tests. A separate API 36 emulator attempt
could not complete a stable software-only boot: system processes crashed and
APK installation was rejected while Android was still booting. Neither APK
was launched in that emulator. The user subsequently confirmed that 0.1.1
opens successfully on their phone and gave positive feedback. This establishes
the launch repair on that phone; the full readability/settings matrix above
has not been individually recorded.

A subsequent API 30 x86_64 software emulator completed startup and verified
0.1.1 installation, cold launch, fixture selection, Show, and Hide. Selecting
100 produced notification ID 117 with the bitmap small icon, expected sample
text, LOW importance, and no sound or vibration; Hide removed the active
notification. A screenshot shows the numeric icon in the system status bar.
The emulator had initial system ANRs during its slow boot, then settled; no
app crash occurred. This is additional Android runtime evidence, not a
substitute for physical-phone readability across themes and settings.

The replacement retains the same package and signing key for an in-place
update. The APK v2 signature verifies; its manifest still requests only
`POST_NOTIFICATIONS`. Test dependencies are excluded from the APK.

`usage-meter-preview-0.1.1.apk` is 848,391 bytes. SHA-256:

```text
8115895bc16b3c6ecd8e6787984628c46b8d59811d0c2ec7b128d5db3e7ec4c1
```

## Native account-library investigation

The next standalone feasibility test uses real upstream `codex-login`,
`codex-backend-client`, and `codex-http-client` libraries, pinned to the source
commit below. The normal Android application loads a JNI shared library; it
does not start the desktop executable or app-server. Its inputs are synthetic,
its request destination is `offline.invalid`, and its unpublished upstream
network policy denies requests before transport. The diagnostic APK also has
no network permission. No existing credentials are opened or copied.

ARM64 `cargo check --locked` succeeds with Rust 1.95.0 and Android NDK
28.2.13676358 targeting API 26, with no changes to upstream source. The wrapper
enables OpenSSL's ordinary `vendored` feature to supply the native TLS dependency
missing from the Android NDK. Shared-library linking and on-device execution
are distinct checks recorded in `android/native/README.md`.

This is a deliberately separate **Usage Meter Native Lab**, not a live meter
or an account connection screen. The current dependency graph contains hundreds
of packages, including storage, image, PTY, and AWS support.
Compilation success does not yet establish a maintainable production boundary.

Before connecting an account, this route still needs Android certificate-trust
validation, credential persistence through Android Keystore, and a complete
phone-owned login/refresh/sign-out lifecycle. The upstream keyring crate has a
public credential-builder hook that is a candidate for the platform storage
adapter. None of those live-account capabilities is claimed by the offline
probe. Successful execution means only that pinned request code loads and
reaches the expected default-deny boundary inside Android.

The first x86_64 diagnostic APK passed Android 11/API 30 installation, cold
launch, JNI loading, and both synthetic request checks. The screen displayed
`OFFLINE CHECK PASSED`, returned the pinned commit and expected expiration,
and reported no credential-store opening or transport attempt. The package's
manifest has no requested permissions, and its app-specific crash buffer was
empty. The existing notification preview remained running alongside it.

The unoptimized x86_64 APK is 46,346,018 bytes, including a 46,324,152-byte
stripped native library. Production size has not been evaluated. This lab APK
is an internal validation artifact; dependency-notice collection and review
are required before external distribution. ARM64 linking and hardware execution
are tracked separately from the x86_64 runtime check.

The next storage boundary and its acceptance tests are documented in
[Android secure storage](android-secure-storage.md). In particular, Android's
default backend in the pinned `keyring` crate is a mock: installing the real
adapter must be mandatory before any login operation can claim persistence.

## Synthetic Android Keystore milestone

The separate 0.1.1-lab diagnostic now implements that adapter for fixed synthetic
fixtures. Java uses Android Keystore AES-GCM with versioned ciphertext under
`noBackupFilesDir`; Rust installs the custom keyring builder behind an explicit
initialization gate. All native operations are serialized and select
`Keyring`/`Direct`, with no plaintext fallback or arbitrary credential input.
Both ARM64 and x86_64 builds pass without upstream source edits.

On Android 11/API 30 x86_64, real upstream save, load, and logout calls passed
save/restart/read, overwrite/restart/read, and delete/restart/read. Each restart
force-stopped and cold-launched the application. App-private inspection found
encrypted fixture data, with no `auth.json` or plaintext fixture/token-field
markers. The isolated Java vault failure suite passed; detailed fault scope
and remaining limits are in the storage document.

The next concrete compatibility gate is Android app-default certificate trust.
The existing upstream account constructors have no public way to supply that
verifier; [the TLS source review](android-tls-boundary.md) identifies a narrow
HTTP-factory adaptation and local test matrix. No TLS adaptation or live login
has been implemented. Production refresh/logout ordering, phone execution,
backup behavior, source maintenance, and external packaging remain separate
requirements. The installed notification preview still contains sample data.

## Sources checked

The desktop baseline is main commit
`1e9c80171203ba8a2f6536dc5a759aa98fc04167`, especially
`src/CodexUsageClient.cs`, `src/TrayIconRenderer.cs`, and
`src/CodexNavigation.cs`.

[OpenAI app-server](https://learn.chatgpt.com/docs/app-server),
[authentication](https://learn.chatgpt.com/docs/auth),
[access tokens](https://learn.chatgpt.com/docs/enterprise/access-tokens), and
[pricing/dashboard link](https://learn.chatgpt.com/docs/pricing).

[Upstream account/login source](https://github.com/openai/codex/tree/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login)
and [quota client source](https://github.com/openai/codex/tree/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/backend-client)
were inspected from a read-only checkout. No account credentials or live
account endpoints were accessed.

[Android PhoneWindow initialization](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/com/android/internal/policy/PhoneWindow.java).

[Android small-icon API](https://developer.android.com/reference/android/app/Notification.Builder),
[importance and icon visibility](https://developer.android.com/reference/android/app/NotificationManager),
[Android 14 dismissal](https://developer.android.com/about/versions/14/behavior-changes-all),
and [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes).
