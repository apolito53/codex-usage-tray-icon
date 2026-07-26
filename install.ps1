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
            [System.IO.Path]::GetFullPath($installedExecutable),
            [System.StringComparison]::OrdinalIgnoreCase)
    } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
    }

New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
Copy-Item -LiteralPath $builtExecutable -Destination $installedExecutable -Force

$pdbPath = [System.IO.Path]::ChangeExtension($builtExecutable, '.pdb')
if (Test-Path -LiteralPath $pdbPath) {
    Copy-Item -LiteralPath $pdbPath -Destination $installRoot -Force
}

$runKey = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($runKeyPath)
try {
    if ($NoStartup) {
        $runKey.DeleteValue($runValueName, $false)
    }
    else {
        $runKey.SetValue($runValueName, "`"$installedExecutable`"")
    }
}
finally {
    $runKey.Dispose()
}

Start-Process -FilePath $installedExecutable -WindowStyle Hidden

Write-Host "Installed and launched $installedExecutable"
if ($NoStartup) {
    Write-Host 'Start with Windows: off'
}
else {
    Write-Host 'Start with Windows: on'
}

