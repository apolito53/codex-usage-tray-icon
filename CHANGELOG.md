# Changelog

## Unreleased

- Added Ubuntu usage history with one-minute polling and private, 90-day
  SQLite storage. Failed polls, restarts, resets, and long gaps do not generate
  invented usage deltas.
- Added native allowance-cycle and Today charts with remaining-balance and
  cumulative views, daily drill-down, and 1h/30m/15m/5m/1m intervals. Burn rates
  use `% of allowance/min` labels. Cycles follow recorded resets and can be
  browsed independently; remaining balances use a fixed 0–100% scale.
- Preserved fractional usage readings for charts, integrated helper-module
  installation, and added an independent `--purge-history` uninstall option.

## v0.2.0.0 - 2026-08-18

- Added an Ubuntu GTK/AppIndicator tray host backed by the signed-in Codex CLI.
- Added PATH, NVM, user-local, asdf, system, and Snap CLI discovery, including
  NVM default-alias selection and the Node PATH repair needed by GNOME
  autostart sessions.
- Added Linux install, autostart, uninstall, diagnostics, dynamic SVG icons,
  stale/offline retry behavior, and a read-only `--check` command.
- Added focused tests for Linux path discovery, usage parsing, reset credits,
  and icon generation while preserving the existing Windows implementation.

## v0.1.5.0 - 2026-10-01

- Resolve the executable's physical backing path when installation runs inside
  an MSIX-packaged terminal, and verify startup in the real desktop registry.
- Launch through the existing Explorer desktop and verify independence from
  the terminal's Windows job so agent cleanup cannot terminate the tray.
- Keep duplicate launches single-instance and remove stale startup registration
  during uninstall even when the executable is already missing.
- Add process, session, version, job-limit, message-loop exit, and exception
  stack-trace diagnostics without logging raw usage-server payloads.

## v0.1.4.0 - 2026-07-29

- Kept the last successful usage, reset time, and reset-credit details visible
  when a refresh fails.
- Added explicit stale/offline labels, a red tray-icon badge, and one-minute
  retries that back off to five minutes while the connection is down.
- Enlarged one-, two-, and three-digit tray-icon text for better legibility.

## v0.1.3.1 - 2026-07-26

- Replaced the unsupported `codex://settings/usage` link, which Codex currently
  normalizes to General settings.
- Opened Codex's supported Settings entry point, selected **Usage & billing**
  through Windows accessibility, and scrolled the reset-expiry list into view.
- Kept the automation deliberately read-only with respect to reset controls and
  added a visible fallback when Codex's accessibility labels cannot be found.

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
