# Codex Usage Tray

A tiny Windows notification-area app that keeps the important Codex number
where it belongs: beside the clock instead of buried in a menu.

The tray icon shows the percentage of the weekly Codex allowance remaining.
Its color shifts from green to amber to red as the allowance gets low. Hover
for a quick summary including available free resets, left-click for reset
details, or right-click to refresh, toggle startup, open the diagnostic log
folder, or exit.

If a refresh cannot reach Codex, the last successful percentage, reset time,
and reset-credit details stay visible as a stale reading. The tray icon gains a
red offline badge, the menu and tooltip say **OFFLINE**, and the app retries
after one minute, then backs off to two and five minutes during a longer
outage.

In the right-click menu, expand **Free resets available** to see the expiry
date for each individual reset credit. Click an expiry row to open Codex
Desktop's **Usage & billing** page and scroll the reset list into view. The
tray app itself never consumes a reset.

Codex does not currently expose **Usage & billing** as a public external deep
link. The tray app opens Codex's supported Settings link, then uses Windows
accessibility to invoke only the **Usage & billing** sidebar item and scroll an
expiry label into view. It never searches for or invokes a **Use reset**
button. If those accessibility labels change, Codex Settings still opens and
the tray reports that the final navigation step needs to be done manually.

## How it gets the number

Codex Usage Tray launches the locally installed Codex CLI app server and reads
`account/rateLimits/read`. It reuses the ChatGPT login already managed by
Codex. The app does not store a password, session token, or API key.

The app-server command is currently marked experimental by Codex. The usage
method is present in the protocol shipped with the installed desktop app, but a
future Codex update could rename or reshape it. Errors are kept visible in the
tray and written to a small local log. A stale reading is always labeled as
offline rather than being passed off as current.

## Install

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

## Build only

No Visual Studio or .NET SDK is required. Windows' .NET Framework C# compiler
is enough:

```powershell
.\build.ps1
.\start.ps1 -ExecutablePath .\bin\CodexUsageTray.exe
```

Requirements:

- Windows 10 or 11
- Codex Desktop installed and signed in with ChatGPT
- .NET Framework 4.8

The automatic jump from Settings to **Usage & billing** currently expects the
English Codex interface.

For an unusual Codex installation, set `CODEX_USAGE_TRAY_CODEX_PATH` to the
full path of a usable `codex.exe`.

## Uninstall

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
%LOCALAPPDATA%\CodexUsageTray\logs\usage-tray.log
```

The app refreshes immediately on launch and every five minutes afterward.
Manual refresh is available from the right-click menu.

To start the installed copy from a terminal or coding agent, run `./start.ps1`.
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
