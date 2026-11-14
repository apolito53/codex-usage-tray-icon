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
$fiveRemaining = $five.GetType().GetProperty('RemainingPercent', $instanceFlags).GetValue($five, $null)
$weekRemaining = $week.GetType().GetProperty('RemainingPercent', $instanceFlags).GetValue($week, $null)
if ($fiveRemaining -ne 75 -or $weekRemaining -ne 58) { throw "Plus dual quota parsing failed: $fiveRemaining / $weekRemaining" }
if (-not $plus.GetType().GetProperty('IsPlus', $instanceFlags).GetValue($plus, $null)) { throw 'Plus plan detection failed' }

$missingResponse = $serializer.DeserializeObject('{"result":{"rateLimits":{"planType":"plus","primary":{"usedPercent":42,"windowDurationMins":10080}}}}')
$missing = $parser.Invoke($null, @($missingResponse))
if ($missing.GetType().GetProperty('FiveHourWindow', $instanceFlags).GetValue($missing, $null)) { throw 'Missing five-hour quota treated as a value' }
# The original circular renderer remains the only tray icon renderer.
$renderer = $assembly.GetType('CodexUsageTray.TrayIconRenderer')
if ($renderer.GetMethod('CreateDualStatusIcon', $flags)) { throw 'Unreadable pill renderer remains' }

# Exercise persisted selection with an isolated temporary path, not the user's settings.
$preference = $assembly.GetType('CodexUsageTray.TrayWindowPreference')
$load = $preference.GetMethod('LoadFromPath', $flags)
$save = $preference.GetMethod('SaveToPath', $flags)
$testPath = Join-Path ([IO.Path]::GetTempPath()) ('codextray-' + [Guid]::NewGuid().ToString('N') + '.txt')
try {
    if ($load.Invoke($null, @($testPath)) -ne 'five-hour') { throw 'Default tray preference must be 5-hour' }
    $save.Invoke($null, @($testPath, 'weekly'))
    if ($load.Invoke($null, @($testPath)) -ne 'weekly') { throw 'Weekly tray selection did not persist' }
    $save.Invoke($null, @($testPath, 'invalid'))
    if ($load.Invoke($null, @($testPath)) -ne 'five-hour') { throw 'Invalid preference must fall back to 5-hour' }
} finally {
    Remove-Item -LiteralPath $testPath -ErrorAction SilentlyContinue
}

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
foreach ($name in @('_summaryItem', '_fiveHourItem', '_fiveHourResetItem', '_trayWindowMenuItem', '_trayFiveHourChoiceItem', '_trayWeeklyChoiceItem', '_resetItem', '_resetCreditsItem', '_prepaidCreditsItem', '_statusItem')) {
    Set-Field $name (New-Object System.Windows.Forms.ToolStripMenuItem)
}
$notify = New-Object System.Windows.Forms.NotifyIcon
$timer = New-Object System.Windows.Forms.Timer
Set-Field '_notifyIcon' $notify
Set-Field '_refreshTimer' $timer
Set-Field '_latestSnapshot' $snapshot
Set-Field '_trayWindowChoice' 'five-hour'
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
    $fiveChoice = $contextType.GetField('_trayFiveHourChoiceItem', $instanceFlags).GetValue($context)
    $weeklyChoice = $contextType.GetField('_trayWeeklyChoiceItem', $instanceFlags).GetValue($context)
    $trayMenu = $contextType.GetField('_trayWindowMenuItem', $instanceFlags).GetValue($context)
    if (-not $trayMenu.Visible) { throw 'Plus tray switch menu is hidden' }
    if (-not $fiveChoice.Checked -or $weeklyChoice.Checked) { throw '5-hour selection not checked' }
    $chosen = Call-Context 'GetSelectedTrayWindow' @($plus)
    $chosenRemaining = $chosen.GetType().GetProperty('RemainingPercent', $instanceFlags).GetValue($chosen, $null)
    if ($chosenRemaining -ne 75) { throw 'Five-hour icon selection failed' }

    Set-Field '_trayWindowChoice' 'weekly'
    Call-Context 'ApplySnapshot' @($plus)
    $chosen = Call-Context 'GetSelectedTrayWindow' @($plus)
    $chosenRemaining = $chosen.GetType().GetProperty('RemainingPercent', $instanceFlags).GetValue($chosen, $null)
    if ($chosenRemaining -ne 58) { throw 'Weekly icon selection failed' }
    if ($fiveChoice.Checked -or -not $weeklyChoice.Checked) { throw 'Weekly selection not checked' }
    # The icon was refreshed by ApplySnapshot without fetching data.
    if (-not $notify.Icon) { throw 'Weekly tray icon not rendered' }
    Call-Context 'ApplyError' @([Exception]::new('Synthetic offline test'))
    if ($fiveItem.Text -ne '5-hour: 75% left - STALE') { throw 'Stale Plus value missing' }

    Set-Field '_latestSnapshot' $missing
    Set-Field '_trayWindowChoice' 'five-hour'
    Call-Context 'ApplySnapshot' @($missing)
    if ($fiveItem.Text -ne '5-hour: unavailable left') { throw 'Unknown Plus quota not explicit' }
    if (Call-Context 'GetSelectedTrayWindow' @($missing)) { throw 'Missing selected quota is not unknown' }
    if (-not $notify.Icon) { throw 'Unknown ?? circular icon not rendered' }
    Set-Field '_latestSnapshot' $snapshot
    Call-Context 'ApplySnapshot' @($snapshot)
    if ($fiveItem.Visible) { throw 'Five-hour row remains visible on Pro' }
    if ($trayMenu.Visible) { throw 'Tray switch appears for Pro' }
    if ($weeklyItem.Text -ne 'Codex: 75% weekly left') { throw 'Pro menu changed' }
    Write-Host 'Passed Windows menu, left-click balloon, offline, recovery, and selectable Plus/Pro icon checks.'
} finally {
    $timer.Stop(); $timer.Dispose(); $notify.Dispose()
    $icon = $contextType.GetField('_currentIcon', $instanceFlags).GetValue($context)
    if ($icon) { $icon.Dispose() }
}
