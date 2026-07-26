# Codebase Index

## Entry points

- `src/Program.cs` - single-instance WinForms startup.
- `src/TrayApplicationContext.cs` - notification icon, menu, refresh timer, and
  user-facing state.

## Usage path

- `src/CodexPathResolver.cs` - finds the mutable local Codex CLI installation.
- `src/CodexUsageClient.cs` - starts `codex app-server`, performs the JSON
  request handshake, and selects the longest/weekly Codex rate-limit window.
- `src/UsageSnapshot.cs` - small UI-facing usage model.
- `src/ResetCreditSnapshot.cs` - read-only expiry data for one available reset
  credit; intentionally excludes opaque redemption identifiers.

## Windows integration

- `src/TrayIconRenderer.cs` - dynamically renders the colored percentage icon.
- `src/StartupRegistration.cs` - manages the current-user Run registry value.
- `src/AppLog.cs` - bounded local diagnostic logging without raw server
  payloads or credentials.

## Common commands

```powershell
.\build.ps1
.\install.ps1
.\uninstall.ps1
```

## Sharp edges

- The Codex app-server protocol is currently experimental. Keep raw protocol
  parsing isolated in `CodexUsageClient.cs`.
- `NotifyIcon.Text` is limited to 63 characters on .NET Framework.
- Windows may initially place a new tray icon in the notification overflow.
- The project intentionally targets the Windows-provided C# 5 compiler, so do
  not introduce newer language syntax without changing the build foundation.
