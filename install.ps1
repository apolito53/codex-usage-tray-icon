[CmdletBinding()]
param(
    [switch] $NoStartup
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildScript = Join-Path $projectRoot 'build.ps1'
$builtExecutable = Join-Path $projectRoot 'bin\CodexUsageTray.exe'
$installRoot = Join-Path $env:LOCALAPPDATA 'Programs\CodexUsageTray'
$installedExecutable = Join-Path $installRoot 'CodexUsageTray.exe'
$runKeyPath = 'Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'CodexUsageTray'

& $buildScript

if (-not ('CodexUsageTrayDesktop' -as [type])) {
    Add-Type -Path (Join-Path $projectRoot 'scripts\DesktopLaunch.cs')
}
$existingPhysicalExecutable = if (Test-Path -LiteralPath $installedExecutable) {
    [CodexUsageTrayDesktop]::ResolvePhysicalPath($installedExecutable)
} else { $installedExecutable }

$resolvedLocalAppData = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA)
$resolvedInstallRoot = [System.IO.Path]::GetFullPath($installRoot)

if (-not $resolvedInstallRoot.StartsWith($resolvedLocalAppData, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to install outside LOCALAPPDATA: $resolvedInstallRoot"
}

# Stop only an earlier installed copy at the exact destination path. Other
# processes with the same filename are not ours to touch.
Get-CimInstance Win32_Process -Filter "Name = 'CodexUsageTray.exe'" |
    Where-Object {
        $_.ExecutablePath -and
        [System.IO.Path]::GetFullPath($_.ExecutablePath).Equals(
            [System.IO.Path]::GetFullPath($existingPhysicalExecutable),
            [System.StringComparison]::OrdinalIgnoreCase)
    } |
    ForEach-Object {
        $processId = $_.ProcessId
        Stop-Process -Id $processId -Force

        # Stop-Process can return before Windows releases the executable
        # image. Wait briefly so an in-place update cannot race Copy-Item.
        $exitDeadline = [DateTime]::UtcNow.AddSeconds(5)
        while (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            if ([DateTime]::UtcNow -ge $exitDeadline) {
                throw "The installed tray process did not exit within five seconds."
            }

            Start-Sleep -Milliseconds 100
        }
    }

New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
Copy-Item -LiteralPath $builtExecutable -Destination $installedExecutable -Force

$pdbPath = [System.IO.Path]::ChangeExtension($builtExecutable, '.pdb')
if (Test-Path -LiteralPath $pdbPath) {
    Copy-Item -LiteralPath $pdbPath -Destination $installRoot -Force
}

$runKey = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($runKeyPath)
try {
    # Remove the older possibly virtualized registration. The desktop
    # launcher registers its physical path in the real user's key below.
    $runKey.DeleteValue($runValueName, $false)
}
finally {
    $runKey.Dispose()
}

$startupMode = if ($NoStartup) { 'Disable' } else { 'Enable' }
& (Join-Path $projectRoot 'start.ps1') -ExecutablePath $installedExecutable -StartupMode $startupMode

$physicalInstalledExecutable = [CodexUsageTrayDesktop]::ResolvePhysicalPath($installedExecutable)
Write-Host "Installed and launched $physicalInstalledExecutable"
if ($NoStartup) {
    Write-Host 'Start with Windows: off'
}
else {
    Write-Host 'Start with Windows: on'
}
