# Current handoff

Android 0.2.0 connects a phone-owned ChatGPT session to the weekly allowance
notification. Start with [the Android guide](android/README.md). OpenAI hosts
the browser approval. The app handles the device code, session renewal and
sign-out using the pinned Codex account libraries and Android Keystore encryption.

The ARM64 and x86_64 libraries built on Linux. On the Android 11 x86_64
emulator, the in-place update from 0.1.1 worked. The device tests used the
actual JNI and Keystore with synthetic provider replies to exercise approval,
quota display values, a 401 with token rotation and retry, sign-out, and a late
response after cancellation. A separate two-process check restored the saved
session after a force-stop and cleared it on sign-out. The signed release APK
installed over the previously shared 0.1.1 preview. OpenAI issued a real
one-time code and the meter opened the external browser. The emulator attempt
was cancelled before account approval. The remaining account-specific check
is to approve the code on the owner's phone and compare the live percentage
with the usage dashboard. [Open checks](TODO.md)

The private sideload APK is 18,823,045 bytes and its SHA-256 is
`805b300a78085eccb9c3272eaf7298f8286db2f5aaec4921190c2e2766c8512d`.

The Windows and Ubuntu trays still read the desktop CLI's selected Codex
bucket. Windows prepaid credits were built, installed and verified in the
October 17 desktop pass. Native Ubuntu menu rendering and purchase-to-billing
reconciliation remain open; see the [desktop notes](CRYSTALIZED_MEMORY.md).
