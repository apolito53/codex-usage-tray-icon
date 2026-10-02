[CmdletBinding()]
param(
    [string] $ExecutablePath = (Join-Path $env:LOCALAPPDATA 'Programs\CodexUsageTray\CodexUsageTray.exe'),
    [ValidateSet('Preserve', 'Enable', 'Disable', 'DisableOnly')]
    [string] $StartupMode = 'Preserve'
)

$ErrorActionPreference = 'Stop'

$physicalExecutable = $null
if ($StartupMode -ne 'DisableOnly') {
    $resolvedExecutable = (Resolve-Path -LiteralPath $ExecutablePath).ProviderPath
    if (-not (Test-Path -LiteralPath $resolvedExecutable -PathType Leaf) -or
        [System.IO.Path]::GetExtension($resolvedExecutable) -ne '.exe') {
        throw "Expected a tray executable: $resolvedExecutable"
    }

    # A packaged terminal can redirect AppData paths and place its children in
    # a kill-on-close job. Resolve the backing file before desktop launch.
    if (-not ('CodexUsageTrayDesktop' -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot 'scripts\DesktopLaunch.cs')
    }
    $physicalExecutable = [CodexUsageTrayDesktop]::ResolvePhysicalPath($resolvedExecutable)
}
if ($StartupMode -ne 'Preserve') {
    # StdRegProv accesses the real desktop registry, not the terminal's
    # possible package overlay. No transient tray process is needed.
    $registryArgs = @{
        hDefKey = [uint32]2147483649
        sSubKeyName = 'Software\Microsoft\Windows\CurrentVersion\Run'
        sValueName = 'CodexUsageTray'
    }
    if ($StartupMode -eq 'Enable') {
        $keyResult = Invoke-CimMethod -Namespace root/default -ClassName StdRegProv `
            -MethodName CreateKey -Arguments @{
                hDefKey = $registryArgs.hDefKey
                sSubKeyName = $registryArgs.sSubKeyName
            }
        if ($keyResult.ReturnValue -ne 0) {
            throw "Could not open the desktop startup key ($($keyResult.ReturnValue))."
        }
        $writeArgs = $registryArgs.Clone()
        $writeArgs.sValue = '"' + $physicalExecutable + '"'
        $startupResult = Invoke-CimMethod -Namespace root/default -ClassName StdRegProv `
            -MethodName SetStringValue -Arguments $writeArgs
        if ($startupResult.ReturnValue -ne 0) {
            throw "Could not register desktop startup ($($startupResult.ReturnValue))."
        }
    } else {
        $startupResult = Invoke-CimMethod -Namespace root/default -ClassName StdRegProv `
            -MethodName DeleteValue -Arguments $registryArgs
        if ($startupResult.ReturnValue -notin @(0, 1, 2)) {
            throw "Could not remove desktop startup ($($startupResult.ReturnValue))."
        }
    }

    $startup = Invoke-CimMethod -Namespace root/default -ClassName StdRegProv `
        -MethodName GetStringValue -Arguments $registryArgs
    $startupVerified = if ($StartupMode -eq 'Enable') {
        $startup.ReturnValue -eq 0 -and
            $startup.sValue -eq ('"' + $physicalExecutable + '"')
    } else {
        $startup.ReturnValue -eq 2 -or $startup.ReturnValue -eq 1
    }
    if (-not $startupVerified) {
        throw "The desktop startup registration was not verified ($StartupMode)."
    }
}

if ($StartupMode -eq 'DisableOnly') {
    Write-Host 'Desktop startup registration removed.'
    return
}
$shell = $null
$windows = $null
$desktop = $null
$desktopShell = $null
try {
    # A new Shell.Application alone can retain the caller's packaged context;
    # FindWindowSW retrieves the existing Explorer desktop instead.
    $shell = New-Object -ComObject Shell.Application
    $windows = $shell.Windows()
    $desktopHandle = 0
    $desktop = $windows.FindWindowSW(0, 0, 8, [ref]$desktopHandle, 1)
    if (-not $desktop) {
        throw 'Explorer is not running in this session. Start the tray after signing in.'
    }

    $desktopShell = $desktop.Document.Application
    $desktopShell.ShellExecute($physicalExecutable, '',
        [System.IO.Path]::GetDirectoryName($physicalExecutable), 'open', 0)
}
finally {
    foreach ($comObject in @($desktopShell, $desktop, $windows, $shell)) {
        if ($null -ne $comObject) {
            [System.Runtime.InteropServices.Marshal]::ReleaseComObject($comObject) | Out-Null
        }
    }
}

$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    $tray = Get-CimInstance Win32_Process -Filter "Name = 'CodexUsageTray.exe'" |
        Where-Object { $_.ExecutablePath -eq $physicalExecutable } |
        Select-Object -First 1
    if ($tray) { break }
    Start-Sleep -Milliseconds 200
} while ([DateTime]::UtcNow -lt $deadline)

if ($tray) {
    # A duplicate copy can appear briefly before discovering the mutex is
    # owned elsewhere. Verify the observed process actually stays running.
    Start-Sleep -Seconds 1
    $tray = Get-CimInstance Win32_Process -Filter "ProcessId = $($tray.ProcessId)" |
        Where-Object { $_.ExecutablePath -eq $physicalExecutable }
}
if (-not $tray -or [CodexUsageTrayDesktop]::InCallerJob([int]$tray.ProcessId)) {
    throw 'An independent desktop tray process was not verified.'
}

Write-Host "Launched $physicalExecutable through Explorer (PID $($tray.ProcessId); terminal job: False)."
