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

**Disposition: standalone Android access has not been established.** The
bounded investigation found an official custom-client integration through
`codex app-server`, with managed browser/device-code sign-in and a quota-read
operation. It did not establish an independently registered Android OAuth
client or hosted subscription-quota API. This is not proof that no such route
can exist. A new client still needs a documented way to acquire and renew its
own authorized account session.

The documented external-token mode assumes a host already owns the user's
authentication lifecycle. Platform API keys do not substitute for a ChatGPT
subscription session. The access-token documentation currently describes
Business/Enterprise automation and does not establish this personal Android
use case. No login was initiated, no auth file was read, and no token or cookie
was copied during the investigation.

The browser destination was extracted from the official pricing page:

https://chatgpt.com/codex/settings/usage

Native Android routing to that exact page remains untested. The preview's
sample notification opens its own screen, so it cannot imply the sample quota
came from the account dashboard.

Before implementing live reads, either establish a supported independent
mobile route or obtain the user's decision on a desktop/server-assisted
snapshot reader. A host-assisted design would keep Codex auth on the chosen
host and expose only normalized quota/reset metadata through authenticated
transport. It would depend on that host being online. No host service,
relay, remote RPC exposure, or new hosting has been added by this phase.

## Gate B: notification readability

The preview lets the user explicitly show a numeric sample, unknown, error,
and stale states. Its notification is labeled as sample data and uses one
silent LOW-importance channel. It has no network permission, credential
handling, scheduled fetch, boot receiver, or foreground service. The preview
posts through Android's actual small-icon API. Physical-phone validation is
still pending; the enlarged icon inside the app does not establish legibility.

The user should inspect one-, two-, and three-digit samples in their real
status bar, plus the stale marker, in light and dark System UI. The device's
notification permission/channel and "hide silent icons" settings control
visibility. A modern Android version can dismiss the ongoing notification;
the preview does not repost it automatically.

Build/lint verification establishes packaging and static correctness only.
Physical-phone legibility and native link routing remain user-device checks.

## Build verification

`./gradlew :app:assembleDebug :app:lintDebug` succeeded with the pinned JDK/SDK.
Lint reports zero errors and two nonblocking warnings: an English-only sample
label and the newer backup-extraction configuration recommendation. Only the
fixture selection is persisted, and cloud backup is disabled. Device-transfer
backup rules need revisiting before any account data is introduced.

`apksigner verify --verbose` verified the debug APK's v2 signature. Inspection
of the packaged manifest confirmed the only requested permission is
`POST_NOTIFICATIONS`, with package `com.apolito.codexusage.preview`, minimum SDK
26, target SDK 36, and version `0.1.0-preview`. No emulator or physical-device
execution was available in this session. There is no claim of runtime or
readability acceptance yet.

Delivered preview: `usage-meter-preview-0.1.0.apk` (849,847 bytes), debug-signed
for this disposable preview, not a production release. Its SHA-256 is:

```text
a7b4c7662b819bd2d40df2c9c85c3ac85be017052520169796cf08a78565a9da
```

## Sources checked

The desktop baseline is main commit
`1e9c80171203ba8a2f6536dc5a759aa98fc04167`, especially
`src/CodexUsageClient.cs`, `src/TrayIconRenderer.cs`, and
`src/CodexNavigation.cs`.

[OpenAI app-server](https://learn.chatgpt.com/docs/app-server),
[authentication](https://learn.chatgpt.com/docs/auth),
[access tokens](https://learn.chatgpt.com/docs/enterprise/access-tokens), and
[pricing/dashboard link](https://learn.chatgpt.com/docs/pricing).

[Android small-icon API](https://developer.android.com/reference/android/app/Notification.Builder),
[importance and icon visibility](https://developer.android.com/reference/android/app/NotificationManager),
[Android 14 dismissal](https://developer.android.com/about/versions/14/behavior-changes-all),
and [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes).
