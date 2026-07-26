# Changelog

## v0.1.3.0 - 2026-07-26

- Made each reset-expiry submenu row clickable.
- Added a focused `codex://settings/usage` navigation boundary so clicking a
  reset opens Codex Desktop's usage settings.
- Kept navigation failures visible in the tray and diagnostic log.

## v0.1.2.0 - 2026-07-26

- Turned the free-reset count into an expandable, read-only tray submenu.
- Added one expiry row per returned reset credit, ordered by the soonest
  expiration date.
- Added an explicit fallback when the available count exceeds the detail rows
  returned by Codex.

## v0.1.1.0 - 2026-07-26

- Added the available free reset-credit count to the tray menu, hover tooltip,
  left-click summary, and diagnostic refresh record.
- Kept missing reset-credit data distinct from a real zero-credit balance.
- Hardened in-place updates and uninstalls by waiting for the old tray process
  to release its executable before replacing or removing files.

## v0.1.0.0 - 2026-07-26

- Added a native Windows notification-area app that displays remaining Codex
  weekly usage as a colored percentage icon.
- Added direct usage refreshes through the signed-in Codex CLI app server.
- Added reset-time details, manual refresh, startup registration, and local
  diagnostic logging.
- Added build, install, and uninstall scripts for the stock Windows .NET
  Framework toolchain.
