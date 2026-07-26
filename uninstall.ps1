[CmdletBinding()]
param(
    [switch] $PurgeLogs
)

$ErrorActionPreference = 'Stop'

$installRoot = Join-Path $env:LOCALAPPDATA 'Programs\CodexUsageTray'
$installedExecutable = Join-Path $installRoot 'CodexUsageTray.exe'
$installedPdb = Join-Path $installRoot 'CodexUsageTray.pdb'
$logRoot = Join-Path $env:LOCALAPPDATA 'CodexUsageTray'
$runKeyPath = 'Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'CodexUsageTray'

$resolvedLocalAppData = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA)
$resolvedInstallRoot = [System.IO.Path]::GetFullPath($installRoot)
$resolvedLogRoot = [System.IO.Path]::GetFullPath($logRoot)

if (-not $resolvedInstallRoot.StartsWith($resolvedLocalAppData, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to uninstall outside LOCALAPPDATA: $resolvedInstallRoot"
}

Get-CimInstance Win32_Process -Filter "Name = 'CodexUsageTray.exe'" |
    Where-Object {
        $_.ExecutablePath -and
        [System.IO.Path]::GetFullPath($_.ExecutablePath).Equals(
            [System.IO.Path]::GetFullPath($installedExecutable),
            [System.StringComparison]::OrdinalIgnoreCase)
    } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
    }

$runKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($runKeyPath, $true)
if ($runKey) {
    try {
        $runKey.DeleteValue($runValueName, $false)
    }
    finally {
        $runKey.Dispose()
    }
}

foreach ($filePath in @($installedExecutable, $installedPdb)) {
    if (Test-Path -LiteralPath $filePath) {
        Remove-Item -LiteralPath $filePath -Force
    }
}

if ((Test-Path -LiteralPath $installRoot) -and
    -not (Get-ChildItem -LiteralPath $installRoot -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $installRoot -Force
}

if ($PurgeLogs -and (Test-Path -LiteralPath $logRoot)) {
    if (-not $resolvedLogRoot.StartsWith($resolvedLocalAppData, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove logs outside LOCALAPPDATA: $resolvedLogRoot"
    }

    Remove-Item -LiteralPath $logRoot -Recurse -Force
}

Write-Host 'Codex Usage Tray was uninstalled.'
if (-not $PurgeLogs) {
    Write-Host "Logs were preserved at $logRoot"
}

