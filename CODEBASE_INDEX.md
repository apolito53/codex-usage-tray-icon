# Codebase Index

## Entry points

- `src/Program.cs` - single-instance WinForms startup.
- `src/TrayApplicationContext.cs` - notification icon, menu, refresh timer, and
  user-facing state.
- `linux/codex_usage_tray.py` - Ubuntu GTK/AppIndicator host,
  Codex CLI protocol client, NVM-aware path resolution, XDG autostart, and
  dynamic SVG tray icons.
- `linux/codex_usage_history.py` - private SQLite samples, 90-day retention,
  continuity checks, local-calendar buckets, and elapsed-time burn rates.
- `linux/codex_usage_chart.py` - lazy GTK/Cairo allowance-cycle and Today
  charts, burn rates, cumulative usage, remaining balances, and day drill-down.

## Usage path

- `src/CodexPathResolver.cs` - finds the mutable local Codex CLI installation.
- `src/CodexUsageClient.cs` - starts `codex app-server`, performs the JSON
  request handshake, and selects the longest/weekly Codex rate-limit window.
- `src/UsageSnapshot.cs` - small UI-facing usage model.
- `src/ResetCreditSnapshot.cs` - read-only expiry data for one available reset
  credit; intentionally excludes opaque redemption identifiers.
- `src/CodexNavigation.cs` - opens Codex's supported Settings deep link, uses
  Windows accessibility to select **Usage & billing**, and scrolls the reset
  expiry list into view without invoking reset controls.

## Windows integration

- `src/TrayIconRenderer.cs` - dynamically renders the large colored percentage
  icon and its stale/offline badge.
- `src/StartupRegistration.cs` - manages the current-user Run registry value.
- `src/AppLog.cs` - bounded local diagnostic logging without raw server
  payloads or credentials.
- `start.ps1` and `scripts/DesktopLaunch.cs` - resolve the physical backing
  EXE and launch through the existing Explorer desktop, avoiding MSIX AppData
  redirection and inherited process jobs. The launcher updates and verifies the
  real desktop startup key through Windows' registry provider.

## Common commands

Ubuntu:

```bash
python3 -m unittest discover -s tests -v
./linux/codex_usage_tray.py --check
./install.sh
./uninstall.sh
# Optional real GTK rendering check; uses synthetic data and a desktop display:
python3 tests/smoke_charts.py /tmp/codex-chart-smoke
```

Windows:

```powershell
.\build.ps1
.\install.ps1
.\uninstall.ps1
```

## Sharp edges

- The Codex app-server protocol is currently experimental. Keep raw protocol
  parsing isolated in `CodexUsageClient.cs` and the corresponding protocol
  section of `linux/codex_usage_tray.py`.
- Ubuntu desktop autostart does not reliably inherit interactive-shell PATH
  changes. The Linux resolver explicitly discovers NVM-managed Codex installs
  and prepends the matching Node bin directory before starting the app server.
- Ubuntu uses AppIndicator, whose normal interaction is the tray menu rather
  than WinForms-style left-click balloons and hover text.
- `NotifyIcon.Text` is limited to 63 characters on .NET Framework.
- A failed refresh keeps the last successful snapshot in memory, labels it
  stale/offline, and retries after one minute with a two- then five-minute
  backoff. A fresh process with no successful snapshot still shows the hard
  unavailable state.
- Ubuntu polls every minute and records only successful readings. Restart,
  recovery, reset/window changes, counter decreases, and long gaps prevent
  delta calculation across an unobserved interval. Keep missing data distinct
  from observed zero usage, and retain fractional readings for history even
  though the tray icon displays an integer.
- Chart rates are labeled `% of allowance/min` and use actual observed time.
  Cumulative totals sum observed increases. Remaining balances use the latest
  sample per interval and a fixed 0–100% scale, including baseline/reset samples.
- Allowance cycles follow reported reset deadlines. Counter resets without a
  new deadline start a fresh window at the first reset reading. Outages and
  restarts do not create cycles; historical day views are clipped to their cycle.
- Linux installation copies the chart/history modules to the XDG data
  directory. Normal uninstall preserves the database; `--purge-history`
  removes it separately from diagnostic logs.
- `tests/test_history.py` covers persistence, gaps, resets, actual elapsed-time
  rates, midnight/DST, and retention. `tests/test_chart.py` covers navigation,
  independent chart modes, missing data, and axis scaling without GTK.
- Windows may initially place a new tray icon in the notification overflow.
- Codex does not currently expose its usage page as an external deep link, so
  the final navigation step relies on English Windows accessibility labels and
  degrades to the base Settings page if those labels change.
- The project intentionally targets the Windows-provided C# 5 compiler, so do
  not introduce newer language syntax without changing the build foundation.
