$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Web.Extensions
$assembly = [Reflection.Assembly]::LoadFile((Join-Path (Split-Path $PSScriptRoot) 'bin\CodexUsageTray.exe'))
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$client = $assembly.GetType('CodexUsageTray.CodexUsageClient')
$parser = $client.GetMethod('ParseUsageResponse', $flags)
$creditType = $assembly.GetType('CodexUsageTray.PrepaidCredits')
$formatter = $creditType.GetMethod('Format', $flags)
$serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$cases = @(
    @('{"balance":"1234.5670","hasCredits":true}', '1234.5670'),
    @('{"balance":"0","hasCredits":false}', '0'),
    @('{"balance":null,"hasCredits":false}', 'unavailable'),
    @('{"hasCredits":true}', 'unavailable'),
    @('null', 'unavailable'),
    @('{"balance":"NaN"}', 'unavailable'),
    @('{"balance":123}', 'unavailable'),
    @('{"balance":"3","unlimited":"true"}', '3'),
    @('{"balance":"3","unlimited":true}', 'unlimited'),
    @('{"balance":"3","hasCredits":false}', '3')
)
$count = 0
foreach ($case in $cases) {
    foreach ($mapped in @($false, $true)) {
        $bucket = '{"primary":{"usedPercent":25,"windowDurationMins":10080},"credits":' + $case[0] + '}'
        $body = if ($mapped) { '"rateLimits":{"primary":{"usedPercent":99}},"rateLimitsByLimitId":{"codex":' + $bucket + '}' } else { '"rateLimits":' + $bucket }
        $response = $serializer.DeserializeObject('{"result":{' + $body + '}}')
        $snapshot = $parser.Invoke($null, @($response))
        $instanceFlags = [Reflection.BindingFlags]'Instance,NonPublic'
        $credits = $snapshot.GetType().GetProperty('PrepaidCredits', $instanceFlags).GetValue($snapshot, $null)
        $actual = $formatter.Invoke($null, @($credits))
        if ($actual -cne ('Prepaid credits: ' + $case[1])) { throw "Unexpected credit text: $actual" }
        if ($snapshot.GetType().GetProperty('RemainingPercent', $instanceFlags).GetValue($snapshot, $null) -ne 75) { throw 'Quota parsing regressed' }
        $count++
    }
}
Write-Host "Passed $count Windows credit parser/formatter cases."


# Exercise the two-window Plus path and verify Pro remains on the circular icon.
$plusResponse = $serializer.DeserializeObject('{"result":{"rateLimits":{"planType":"plus","primary":{"usedPercent":25,"windowDurationMins":300,"resetsAt":1800000000},"secondary":{"usedPercent":42,"windowDurationMins":10080,"resetsAt":1900000000}}}}')
$plus = $parser.Invoke($null, @($plusResponse))
$instanceFlags = [Reflection.BindingFlags]'Instance,NonPublic'
$five = $plus.GetType().GetProperty('FiveHourWindow', $instanceFlags).GetValue($plus, $null)
$week = $plus.GetType().GetProperty('WeeklyWindow', $instanceFlags).GetValue($plus, $null)
if ($five.RemainingPercent -ne 75 -or $week.RemainingPercent -ne 58) { throw 'Plus dual quota parsing failed' }
if (-not $plus.IsPlus) { throw 'Plus plan detection failed' }

$missingResponse = $serializer.DeserializeObject('{"result":{"rateLimits":{"planType":"plus","primary":{"usedPercent":42,"windowDurationMins":10080}}}}')
$missing = $parser.Invoke($null, @($missingResponse))
if ($missing.FiveHourWindow) { throw 'Missing five-hour quota treated as a value' }
$renderer = $assembly.GetType('CodexUsageTray.TrayIconRenderer')
$dual = $renderer.GetMethod('CreateDualStatusIcon', $flags)
$unknown = $dual.Invoke($null, @('??', [Drawing.Color]::DimGray, '58', [Drawing.Color]::Green, $false))
if (-not $unknown) { throw 'Failed to render unknown-value Plus capsule' }
$unknown.Dispose()

Add-Type -AssemblyName System.Windows.Forms
$contextType = $assembly.GetType('CodexUsageTray.TrayApplicationContext')
$context = [Runtime.Serialization.FormatterServices]::GetUninitializedObject($contextType)
function Set-Field($name, $value) { $contextType.GetField($name, $instanceFlags).SetValue($context, $value) }
function Call-Context($name, $values) {
    $arguments = New-Object object[] $values.Length
    for ($i = 0; $i -lt $values.Length; $i++) {
        $arguments[$i] = if ($null -eq $values[$i]) { $null } else { $values[$i].PSObject.BaseObject }
    }
    $contextType.GetMethod($name, $instanceFlags).Invoke($context, $arguments)
}
foreach ($name in @('_summaryItem', '_fiveHourItem', '_fiveHourResetItem', '_resetItem', '_resetCreditsItem', '_prepaidCreditsItem', '_statusItem')) {
    Set-Field $name (New-Object System.Windows.Forms.ToolStripMenuItem)
}
$notify = New-Object System.Windows.Forms.NotifyIcon
$timer = New-Object System.Windows.Forms.Timer
Set-Field '_notifyIcon' $notify
Set-Field '_refreshTimer' $timer
Set-Field '_latestSnapshot' $snapshot
$row = $contextType.GetField('_prepaidCreditsItem', $instanceFlags).GetValue($context)
try {
    Call-Context 'ApplySnapshot' @($snapshot)
    if ($row.Text -ne 'Prepaid credits: 3') { throw 'Menu balance missing' }
    $click = New-Object System.Windows.Forms.MouseEventArgs([System.Windows.Forms.MouseButtons]::Left, 1, 0, 0, 0)
    Call-Context 'OnTrayMouseClick' @($null, $click)
    if (-not $notify.BalloonTipText.Contains('Prepaid credits: 3')) { throw 'Left-click balance missing' }
    Call-Context 'ApplyError' @([Exception]::new('Synthetic offline test'))
    if ($row.Text -ne 'Prepaid credits: 3 - STALE') { throw 'Stale balance missing' }
    Call-Context 'ApplySnapshot' @($snapshot)
    if ($row.Text -ne 'Prepaid credits: 3') { throw 'Recovery did not clear stale state' }
    Set-Field '_latestSnapshot' $plus
    Call-Context 'ApplySnapshot' @($plus)
    $fiveItem = $contextType.GetField('_fiveHourItem', $instanceFlags).GetValue($context)
    $weeklyItem = $contextType.GetField('_summaryItem', $instanceFlags).GetValue($context)
    if ($fiveItem.Text -ne '5-hour: 75% left') { throw 'Plus menu missing five-hour quota' }
    if ($weeklyItem.Text -ne 'Weekly: 58% left') { throw 'Plus menu missing weekly quota' }
    Call-Context 'ApplyError' @([Exception]::new('Synthetic offline test'))
    if ($fiveItem.Text -ne '5-hour: 75% left - STALE') { throw 'Stale Plus value missing' }

    Set-Field '_latestSnapshot' $missing
    Call-Context 'ApplySnapshot' @($missing)
    if ($fiveItem.Text -ne '5-hour: unavailable left') { throw 'Unknown Plus quota not explicit' }
    Set-Field '_latestSnapshot' $snapshot
    Call-Context 'ApplySnapshot' @($snapshot)
    if ($fiveItem.Visible) { throw 'Five-hour row remains visible on Pro' }
    if ($weeklyItem.Text -ne 'Codex: 75% weekly left') { throw 'Pro menu changed' }
    Write-Host 'Passed Windows menu, left-click balloon, offline, recovery, Plus and Pro checks.'
} finally {
    $timer.Stop(); $timer.Dispose(); $notify.Dispose()
    $icon = $contextType.GetField('_currentIcon', $instanceFlags).GetValue($context)
    if ($icon) { $icon.Dispose() }
}
