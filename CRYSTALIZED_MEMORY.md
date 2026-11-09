# Durable project notes

- The standalone Android app uses `com.apolito.codexusage.meter`. An existing
  0.2.0 session can be updated under `com.apolito.codexusage.preview` using
  `-PupdatePreviewPackage=true` and its original signing key. Keep the
  signing key local. Its native account library uses Codex’s managed device authorization
  and passive allowance client. Android’s HTTPS transport sends the account
  requests, and permanent credentials use Android Keystore
  encryption. See [Android setup](android/README.md) for the build inputs.

- Windows uses .NET Framework and its C# compiler; reflection tests loading
  System.Web.Extensions need Windows PowerShell 5.1, not PowerShell 7.
- Purchased credits are `credits` on the selected rate-limit bucket, with
  `balance: string | null`, `unlimited: boolean`, and `hasCredits: boolean`.
  Verified against the installed CLI's generated protocol on 2026-10-17.
  `hasCredits` alone does not establish a numeric balance. Keep decimal strings
  without floating-point conversion; do not infer dollars or free resets.
- Free reset credits are a separate top-level `rateLimitResetCredits` summary.
- Successful missing credit data clears a prior balance to unavailable. Failed
  requests retain the last complete snapshot with offline/stale indicators.
- Preserve the physical-path desktop launcher and real Windows startup
  registration described in README.md; MSIX AppData can be redirected.
