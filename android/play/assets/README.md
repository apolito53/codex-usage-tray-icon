# Store artwork

The supplied icon preserves the purple circle and geometric “72” from the actual
Android launcher resource, with an opaque lavender square behind the mark for
Play's own masking. The feature graphic is promotional artwork using the same
palette and number geometry; it is not a fabricated screenshot. No OpenAI logo,
Google Play badge, device frame, ranking, testimonial, or price claim is included.
“Example reading” explicitly labels the illustrated percentage.

| Upload field | File | Format | Alt text |
| --- | --- | --- | --- |
| App icon | [icon-512.png](icon-512.png) | 512 × 512, RGBA PNG, opaque full-square background, under 1 MB | White geometric 72 on a purple circle over a pale lavender square, the Allowance Meter icon. |
| Feature graphic | [feature-graphic-1024x500.png](feature-graphic-1024x500.png) | 1024 × 500, RGB PNG with no alpha | Allowance Meter: A little number. A useful place. An example 72 percent weekly allowance reading. |

Regenerate with `python render_assets.py` using Pillow. Pass `--font` and
`--bold-font` paths on systems without Segoe UI. The generator uses fonts
installed on the rendering machine and does not redistribute them. The numeric
icon paths are explicit geometry copied from the project's launcher resource,
not a font approximation.

[artwork-proof.png](artwork-proof.png) is a review contact sheet, **not an upload
asset**. It places the rendered icon on light/dark backgrounds and at small size.

## Actual phone screenshots

The release owner captured these from the app's built-in demo in an emulator.
Both are 1080 × 1920. The in-app and notification “demo/example” labels remain
visible. Export screenshots as RGB PNG without alpha for Play upload; the direct
Android captures can contain an opaque alpha channel. Format conversion should
preserve their pixels and layout.

| Order | Filename | Capture | Suggested alt text |
| --- | --- | --- | --- |
| 1 | [phone-01-meter-demo.png](screenshots/phone-01-meter-demo.png) | Actual meter screen with 72% example, Demo · example data, Refresh, Exit demo, Show meter, and the numeric status-bar icon. | Allowance Meter demo showing a 72 percent example weekly reading and meter controls. |
| 2 | [phone-02-notification-demo.png](screenshots/phone-02-notification-demo.png) | Actual expanded silent notification with example percentage, reset/update time, Hide meter, and DEMO · EXAMPLE DATA wording. | Expanded demo notification showing example weekly allowance, reset time, update time, and Hide meter. |

Use a clean emulator with no private account, unrelated notifications, carrier
branding, or exposed one-time codes. Do not crop out the example label, stretch
the capture, replace the System UI, or overlay claims that imply a live account
was verified. No artificial phone border is needed.

[Google's preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
specify a 512-pixel icon, a 1024 × 500 feature graphic, and accurate screenshots.
The icon is 32-bit PNG; the feature graphic is 24-bit PNG without alpha. Recheck
the current screenshot constraints in Console before uploading. The alt text
above is kept under 140 characters.

[Google's icon design specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)
calls for a full-square asset; Google Play adds its own masking and shadow. The
supplied upload has no baked outer corner radius or drop shadow.
