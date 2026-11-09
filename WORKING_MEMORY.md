# Current handoff

Android 0.2.1 is the standalone phone meter. Install the APK, open **Codex
Usage Meter**, and approve its ChatGPT device code. The current package
`com.apolito.codexusage.meter` can coexist with the September preview. A
working 0.2.0 install can retain its original package and session through the
[legacy update build option](android/README.md#build).

The phone installer showed a generic rejection for 0.2.0. On Android 11 we
reproduced an update-signature conflict with a differently signed 0.1.1,
then installed the signed 0.2.1 APK beside it and cold-launched both. The
0.2.0 native binaries also had 4 KB ELF alignment. The new build verifies
16 KB load segments in both ABIs. The signed APK installed and reached the
sign-in screen on an Android 15 x86_64 emulator reporting 16,384-byte pages.

Android lint and 22 unit tests passed. The actual native device grant, usage
read, token rotation and sign-out passed with synthetic credentials under
the standalone package on Android 11. The 0.2.0 account implementation had
also been checked through process death and a real, unapproved OpenAI
device code. The owner’s physical-phone install and live percentage still
need confirmation. [Open checks](TODO.md)

The 0.2.1 private sideload APK is 18,823,045 bytes. Its SHA-256 is
`f8eb11b218ac62b12f5accc7bb2edcf2854203482829ae3a5571859b4197e0e0`.

The Windows and Ubuntu trays read the desktop CLI’s selected Codex quota
bucket. Native Ubuntu menu rendering and purchase-to-billing reconciliation
remain open in [TODO](TODO.md).
