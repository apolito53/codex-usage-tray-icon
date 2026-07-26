# Changelog

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
