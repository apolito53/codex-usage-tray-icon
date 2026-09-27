# Android notification preview

This Phase 0 APK tests whether the desktop-style numeric usage icon is readable
in an Android status bar. It contains **sample data only**: no account sign-in,
network permission, live usage source, background service, or scheduled work.
It does not demonstrate that standalone Android usage access works.

Use preview **0.1.1** or later. Version 0.1.0 requested the system-bar controller
before creating the window's decor and could crash on launch on Android 11+.
Version 0.1.1 corrects that initialization order and installs over 0.1.0.

The UI uses a small native Kotlin activity for this disposable spike. This does
not choose the long-term UI framework or architecture for any future app.

## Build and install

Use JDK 17, Android SDK 36, and Gradle 8.11.1 via the checked-in wrapper:

```sh
cd android
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`. The package name is
`com.apolito.codexusage.preview`; it installs as **Usage Meter Preview**.
The debug APK is only for the preview, not a signed production delivery.

The startup regression test uses Robolectric's Android 11 and Android 14
frameworks to exercise cold launch, recreation, and the absence of unsolicited
notifications. Reintroducing 0.1.0's early controller access fails both cases.
These are JVM framework tests, not a substitute for the phone check below.

## Check on a phone

Open the app and tap **Show preview**. Android 13 or later requests notification
permission at this point. Nothing is posted simply by opening the app.

Choose 0, 9, 10, 72, 99, and 100. Check the actual status-bar icon at normal
viewing distance and pull down the notification drawer. Unknown (`?`) and error
(`!`) are separate fixtures. Toggle **Stale / offline sample** to inspect a
monochrome × badge beside the last sample number. Error and unknown can also
display the badge without inventing a cached numeric reading.

The same notification ID is updated silently. The expanded content is marked
as sample data, shows simulated usage and reset details, and offers **Hide
preview**. Tap the notification to return to the sample picker. Actual native
or browser reset-page navigation is intentionally unimplemented in this spike.

Check light and dark mode, large system font settings, and portrait/landscape.
The activity applies system-bar and display-cutout insets for edge-to-edge
Android versions. It preserves the selected fixture between launches but never
automatically posts a dismissed notification. It does not start on boot.

If permission is denied or the channel is blocked, **Notification settings**
opens the applicable app or channel screen. Grant access, return, and press
**Show preview** again. Some phones hide silent status-bar icons by default, or
show only a limited number; this app cannot override that system preference.
An ongoing notification can still be dismissible on recent Android versions.

## Icon decision to validate

The renderer mirrors the desktop's 64 px outlined circle and bold centered
number, but its background is transparent because Android tints small icons
from their alpha mask. The offline mark uses geometry rather than a red dot.
Three-digit values use smaller type to preserve `100` within the circle.

`Notification.Builder.setSmallIcon(Icon)` accepts a generated bitmap, so this
preview uses the renderer directly. Pre-generated drawable resources remain an
option if the device check shows a compatibility or readability problem. The
large icon on the setup screen is a composition preview, not proof that its
tiny system-rendered counterpart is legible.

## Pass criteria

The real phone must show the six numeric samples, unknown, error, and stale
without confusing 0 with missing data. `100` must be readable; the stale mark
must survive Android's tint. Permission denial, a blocked channel, and Hide
must behave honestly. Updates must reuse one notification and stay silent with
the default channel settings. Record actual device, Android version, theme,
and any status-bar setting needed before treating the icon design as proven.
