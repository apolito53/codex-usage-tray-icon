# Codex Usage Tray

A tiny Windows and Ubuntu notification-area app that keeps the important Codex
number where it belongs: beside the clock instead of buried in a menu.

The tray icon shows the percentage of the weekly Codex allowance remaining.
Its color shifts from green to amber to red as the allowance gets low. On
Windows, hover for a quick summary, left-click for reset details, or
right-click for controls. Ubuntu's AppIndicator opens the same details and
controls from its normal tray menu.

**Prepaid credits** appears in the Windows left-click summary and right-click
menu, and in Ubuntu's tray menu and `--check` output. It shows the credit
balance reported by the signed-in Codex CLI account, separately from free
resets. Decimal precision is preserved; no currency conversion is assumed.
Missing balances show **unavailable**, and failed refreshes retain the last
balance with a **STALE** label in the menu. A recent purchase appears once
Codex reports it through the app-server usage response.

If a refresh cannot reach Codex, the last successful percentage, reset time,
and reset-credit details stay visible as a stale reading. The tray icon gains a
red offline badge, the menu and tooltip say **OFFLINE**, and the app retries
after one minute, then backs off to two and five minutes during a longer
outage.

In the tray menu, expand **Free resets available** to see the expiry date for
each individual reset credit. On Windows, click an expiry row to open Codex
Desktop's **Usage & billing** page and scroll the reset list into view. The
tray app itself never consumes a reset. Ubuntu keeps those rows read-only
because a CLI-only install has no Codex Desktop page to open.

Codex does not currently expose **Usage & billing** as a public external deep
link. The tray app opens Codex's supported Settings link, then uses Windows
accessibility to invoke only the **Usage & billing** sidebar item and scroll an
expiry label into view. It never searches for or invokes a **Use reset**
button. If those accessibility labels change, Codex Settings still opens and
the tray reports that the final navigation step needs to be done manually.

## Usage graphs on Ubuntu

Open **Usage graphs…** in the tray menu. The native GTK window has two main views:

- **Allowance cycle** opens to **% Remaining**, with a fixed 0–100% scale
  and the latest recorded balance in each five-minute interval. **Daily usage**
  bars and **Cumulative** usage are also available. The dates follow the
  reported weekly reset window. A detected reset starts a new seven-day cycle;
  restarts and offline gaps stay in the same cycle. The arrows browse recorded
  cycles, including ones cut short by an early reset.
- **Today** opens to **Burn rate**, with **Cumulative** and **% Remaining**
  available too. Choose **1h / 30m / 15m / 5m / 1m** intervals.
- Click a cycle's daily bar or balance point (or select it with the arrow keys
  and press Enter) to inspect that day's readings. Historical day details stay
  within the selected allowance cycle.
- Hover or use Left / Right to inspect a reading; Escape hides the window.
  Charts update with the tray's regular polls, and **Refresh** requests a new
  reading.

Ubuntu polls every minute while online and retains 90 days of local history.
The initial view is today's one-minute burn rate. A rate needs two successful
readings, so history begins after launch; it cannot recover earlier activity.

Usage is labeled **% of allowance**, and burn rate is **% of allowance/min**.
A change from 30% to 32% used consumes 2% of the full allowance. Burn rate
divides each observed increase by the actual minutes between polls; larger
chart intervals show a weighted average rate. **Cumulative** sums observed
increases in the selected day or cycle. A day's total can exceed 100% if it
spans resets. **% Remaining** shows the actual balance at the latest reading
in each interval, including the first reading after a reset, on a 0–100% scale.
The header also shows the latest remaining balance and observed usage today.

The current version charts the weekly allowance; separate five-hour allowance
support is not included yet.

This is account-wide usage, so it lets you visually compare periods when you
run different models or effort levels. It does not label tasks or models, and
concurrent work contributes to the same readings. Rounded or delayed readings
can make the one-minute view spiky; the longer intervals make trends easier to
compare. Increases are placed at the time the poll observed them.

Offline periods, restarts, resets, and counter decreases break the series.
Unknown intervals stay blank and shaded, and incomplete larger intervals are
marked. Cumulative totals include only observed increases, with gaps left
visible. A confirmed unchanged reading is a real zero.

The private SQLite history stores only timestamps, allowance percentages,
window/reset times, and continuity markers—no prompts, conversations, model
names, tokens, or account identifiers:

```text
${XDG_STATE_HOME:-~/.local/state}/codex-usage-tray/usage-history.sqlite3
```

The directory is mode `0700` and the database is `0600`. History survives an
upgrade, restart, or normal uninstall. Windows retains its existing tray UI
and five-minute polling; these native graphs are currently Ubuntu-only.

## How it gets the number

Codex Usage Tray launches the locally installed Codex CLI app server and reads
`account/rateLimits/read`. It reuses the ChatGPT login already managed by
Codex Desktop on Windows or Codex CLI on Ubuntu. The app does not store a
password, session token, or API key.

The app-server command is currently marked experimental by Codex. The usage
method is present in the protocol shipped with the installed Codex
distribution, but a future Codex update could rename or reshape it. Errors are
kept visible in the tray and written to a small local log. A stale reading is
always labeled as offline rather than being passed off as current.

## Install on Ubuntu

Install the small native tray dependencies if they are not already present:

```bash
# Ubuntu 22.04 and newer
sudo apt install python3-gi python3-gi-cairo gir1.2-ayatanaappindicator3-0.1

# Ubuntu 20.04
sudo apt install python3-gi python3-gi-cairo gir1.2-appindicator3-0.1
```

Then install and launch the tray:

```bash
git clone https://github.com/apolito53/codex-usage-tray-icon.git
cd codex-usage-tray-icon
./install.sh
```

The installer copies the launcher to `~/.local/bin/codex-usage-tray`, creates a
desktop entry, enables GNOME autostart, and launches it. Pass `--no-startup` to
leave autostart disabled. Chart/history helpers are installed under
`${XDG_DATA_HOME:-~/.local/share}/codex-usage-tray`.

The Linux resolver honors user-level `PATH` entries and NVM's default alias,
and also understands installs under `~/.local/bin`, `~/.npm-global`, asdf,
`/usr/local/bin`, `/usr/bin`, and Snap. This matters at login because GNOME
does not normally source the shell configuration that adds NVM to `PATH`; a
system or Snap CLI must not silently outrank the user's configured NVM CLI. To
verify the CLI and account path without starting a tray icon:

```bash
./linux/codex_usage_tray.py --check
```

Requirements:

- Ubuntu with AppIndicator support (the standard Ubuntu GNOME desktop includes it)
- Python 3.8 or newer with PyGObject and `python3-gi-cairo` for charts
- Codex CLI installed and signed in with ChatGPT

## Install on Windows

From PowerShell:

```powershell
git clone https://github.com/apolito53/codex-usage-tray-icon.git
cd codex-usage-tray-icon
.\install.ps1
```

The installer builds the app, copies it to
`%LOCALAPPDATA%\Programs\CodexUsageTray`, registers it to start with Windows,
and launches it.

Windows decides whether a new notification icon appears directly beside the
clock or under the `^` overflow menu. If it lands in overflow, drag it onto the
visible tray once and Windows should remember.

## Build on Windows

No Visual Studio or .NET SDK is required. Windows' .NET Framework C# compiler
is enough:

```powershell
.\build.ps1
.\start.ps1 -ExecutablePath .\bin\CodexUsageTray.exe
```

Windows requirements:

- Windows 10 or 11
- Codex Desktop installed and signed in with ChatGPT
- .NET Framework 4.8

The automatic jump from Settings to **Usage & billing** currently expects the
English Codex interface.

For an unusual Codex installation on either platform, set
`CODEX_USAGE_TRAY_CODEX_PATH` to the full path of a usable `codex` executable.

## Uninstall

Ubuntu:

```bash
./uninstall.sh
```

Pass `--purge-logs` to remove the diagnostic logs as well, or
`--purge-history` to remove the recorded usage history. The flags can be
combined; purging logs alone preserves history.

Windows:

```powershell
.\uninstall.ps1
```

Add `-PurgeLogs` to remove the diagnostic logs as well.
For a desktop-launched tray, run that option from an ordinary desktop
PowerShell window; an MSIX-packaged terminal can see an older private log
folder instead of the desktop log folder.

## Diagnostics

The rolling log is stored at:

```text
# Ubuntu
${XDG_STATE_HOME:-~/.local/state}/codex-usage-tray/usage-tray.log

# Windows
%LOCALAPPDATA%\CodexUsageTray\logs\usage-tray.log
```

The app refreshes immediately on launch, then every minute on Ubuntu or every
five minutes on Windows. Manual refresh is available from the tray menu.

On Windows, to start the installed copy from a terminal or coding agent, run
`./start.ps1`.
The launcher resolves the EXE's physical backing path and asks the existing
Explorer desktop to start it, then verifies that it is outside the terminal's
Windows job (other Windows jobs may be used by the desktop).
This matters for MSIX-packaged terminals: their AppData files and registry
writes can be redirected into a package cache that other desktop apps cannot
see through the logical path. Their child processes can also inherit a job
that ends when the agent session closes. The launcher registers the physical
path through Windows' desktop registry provider and reads it back to verify
the real user startup key. Explorer must already
be running; no service or scheduled task is used.

The desktop process writes its logs in the real user's LocalAppData folder,
which can differ from the folder a packaged terminal sees at the same path.
Startup logs include the process ID, session ID, version, and Windows job
membership. Exceptions include their stack traces, and a normal message-loop
exit is logged separately from a refresh failure.


## Planning

- [Current handoff](WORKING_MEMORY.md) - implementation state and verification.
- [Durable project notes](CRYSTALIZED_MEMORY.md) - protocol and integration constraints.
- [Actionable work](TODO.md) - concrete, current tasks.
- [Ideas and suggestions](SPITBALL.md) - rough possibilities to explore.
