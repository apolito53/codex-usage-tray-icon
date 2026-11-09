# Current handoff

**Allowance Meter 0.3.0** (version code 5, `com.apolito.allowancemeter`)
is prepared for Google Play. It has not been uploaded or submitted.
The [store listing, screenshots and app-access instructions](android/play/README.md)
and [public privacy policy](https://apolito53.github.io/codex-usage-tray-icon/privacy/)
are ready. Developer enrollment and closed testing are next; see [TODO](TODO.md).

The signed upload bundle is 18,932,840 bytes, SHA-256
`1b54d6b3282c5e8bee7c1d0f0c6bd44fed45697ab7fea4dafbd92d2b5135f6a0`.
Use the [Play build instructions](android/README.md#google-play-build) to
make subsequent releases. The notice archive came from [the passing license workflow](https://github.com/apolito53/codex-usage-tray-icon/actions/runs/42544187369).
The AAB deliberately retains the phone-tested ARM64 and x86_64 binaries
from [native build 42538905597](https://github.com/apolito53/codex-usage-tray-icon/actions/runs/42538905597);
both packaged libraries match that artifact byte for byte. The native source
and build inputs were unchanged. [Release-commit native CI](https://github.com/apolito53/codex-usage-tray-icon/actions/runs/42544897081)
also passed both ABIs. Independent Rust/OpenSSL builds are not byte-identical.
The [notice inventory](android/native/licenses/README.md) distinguishes
five missing original license files from zero unresolved distribution reviews.

All 28 Android JVM tests and nine notice-provenance tests passed. Release
lint found no errors. The Play package's real native auth, refresh, logout
and process restart passed on Android 11 with synthetic credentials. APK
splits generated from the signed bundle installed on Android 15 with 16 KB
pages. The demo is labeled in both the app and notification and the privacy
policy is readable offline.

The 0.2.1 sideload showed a live percentage on a physical phone. Its
package `com.apolito.codexusage.meter` and session remain independent of the
Play installation. The older preview can also be kept; see the
[legacy update build option](android/README.md#build).

The Windows and Ubuntu trays read the desktop CLI’s selected Codex quota
bucket. Native Ubuntu menu rendering and purchase-to-billing reconciliation
remain open in [TODO](TODO.md).
