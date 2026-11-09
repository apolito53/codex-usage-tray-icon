# Allowance Meter: Play handoff

English store materials for **Allowance Meter 0.3.0**, version code **5**, package
**`com.apolito.allowancemeter`**. This is a separate Play package from the private
meter installs. These files prepare a submission; they do not record a Play
Console submission, policy approval, or release approval.

## Copy and review

| Console field or task | Source |
| --- | --- |
| App name | [title.txt](listing/en-US/title.txt) |
| Short description | [short-description.txt](listing/en-US/short-description.txt) |
| Full description | [full-description.txt](listing/en-US/full-description.txt) |
| What's new for 0.3.0 | [release-notes.txt](listing/en-US/release-notes.txt) |
| App access | [Console text](listing/en-US/app-access.txt) and [access details](app-access.md) |
| Declarations and unresolved owner decisions | [policy-review.md](policy-review.md) |
| Store images, alt text, and screenshot slots | [assets/README.md](assets/README.md) |
| Public privacy page source | [../../docs/privacy/index.html](../../docs/privacy/index.html) |

Use **Tools** as the proposed category. Distribution is intended to be free,
local, and noncommercial, with no ads or in-app purchases. The account owner
still completes the Console's business, country, audience, contact, and legal
questions. Do not infer those answers from this repository.

The [public privacy policy](https://apolito53.github.io/codex-usage-tray-icon/privacy/)
is served by GitHub Pages. The in-app policy also works offline. Recheck the
URL when submitting a new release. For the signed bundle and upload-key
instructions, see the [Google Play build](../README.md#google-play-build).

## Final package checks

- Read the final AAB's package, version, target/minimum SDK, permissions, native
  ABIs, signing/upload configuration, and dependency notices against the release
  handoff. Keep upload and signing secrets out of this folder.
- Install the exact release candidate and follow both the demo and live account
  checks. Retain the demo labels in screenshots and review instructions.
- Upload the two [actual emulator screenshots](assets/screenshots) in the
  order in [assets/README.md](assets/README.md). Both are RGB and show the
  built-in demo.
- Recheck description lengths and image dimensions after any edit. Google's
  [listing limits](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
  are 30 characters for the title, 80 for the short description, and 4,000 for
  the full description. The
  [release-note limit](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en)
  is 500 Unicode characters per language.
- Finish the unresolved items in [policy-review.md](policy-review.md) before
  asserting the package is ready for review. The worksheet contains factual
  preparation, not completed declarations.

The asset generator is [assets/render_assets.py](assets/render_assets.py). It
uses Pillow and installed system fonts; it does not redistribute a font or use
third-party logos. Its icon geometry mirrors the app's launcher vector.
