# Build and sign with the local upload key. This script does not upload to Google.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Bundletool,
    [string]$Python = 'python',
    [string]$NativeDir = (Join-Path $PSScriptRoot 'native/out'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'app/build/outputs/play')
)
$ErrorActionPreference = 'Stop'
$Bundletool = (Resolve-Path -LiteralPath $Bundletool).Path
$NativeDir = (Resolve-Path -LiteralPath $NativeDir).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$jarsigner = Join-Path $env:JAVA_HOME 'bin/jarsigner.exe'
$signing = Join-Path $env:LOCALAPPDATA 'AllowanceMeter/PlaySigning'
$store = Join-Path $signing 'upload.p12'
if (-not (Test-Path -LiteralPath $store -PathType Leaf)) { throw 'Upload key missing. See Android Play build instructions.' }
Push-Location -LiteralPath $PSScriptRoot
try {
    & .\gradlew.bat :app:bundleRelease '-PplayDistribution=true' "-PaccountNativeDir=$NativeDir" --console=plain
    if ($LASTEXITCODE) { throw 'Gradle failed to build the Play bundle.' }
} finally { Pop-Location }
$bundle = Join-Path $PSScriptRoot 'app/build/outputs/bundle/release/app-release.aab'
& $java -jar $Bundletool validate "--bundle=$bundle" | Out-Null
if ($LASTEXITCODE) { throw 'bundletool validation failed.' }
[xml]$manifest = ((& $java -jar $Bundletool dump manifest "--bundle=$bundle") -join [Environment]::NewLine)
if ($LASTEXITCODE) { throw 'Could not decode the manifest.' }
$a = 'http://schemas.android.com/apk/res/android'
if ($manifest.manifest.package -ne 'com.apolito.allowancemeter' -or
    $manifest.manifest.application.GetAttribute('debuggable', $a) -eq 'true' -or
    $manifest.manifest.application.GetAttribute('extractNativeLibs', $a) -ne 'false') {
    throw 'Wrong Play identity, debug flag, or native packaging.'
}
$version = $manifest.manifest.GetAttribute('versionName', $a)
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected release version.' }
$configuration = ((& $java -jar $Bundletool dump config "--bundle=$bundle") -join [Environment]::NewLine) | ConvertFrom-Json
if ($LASTEXITCODE -or $configuration.optimizations.uncompressNativeLibraries.alignment -ne 'PAGE_ALIGNMENT_16K') {
    throw 'Bundle does not request 16 KB APK alignment.'
}
& $Python (Join-Path $PSScriptRoot 'verify-native-pages.py') $bundle
if ($LASTEXITCODE) { throw 'Packaged native ELF page alignment failed.' }
& $Python (Join-Path $PSScriptRoot 'verify-play-notices.py') $bundle $NativeDir
if ($LASTEXITCODE) { throw 'The packaged dependency review or native inputs failed verification; bundle not signed.' }
$destination = [IO.Directory]::CreateDirectory($OutputDirectory).FullName
$final = Join-Path $destination "allowance-meter-$version.aab"
$temp = Join-Path $destination ([guid]::NewGuid().ToString('N') + '.tmp')
if (Test-Path -LiteralPath $final) { throw "Output already exists. Choose another output directory to preserve it: $final" }
$secret = Get-Content -Raw -LiteralPath (Join-Path $signing 'password.dpapi')
$secure = ConvertTo-SecureString $secret.Trim()
try {
    $env:ALLOWANCE_JARSIGN_PASS = [PSCredential]::new('upload', $secure).GetNetworkCredential().Password
    & $jarsigner -keystore $store -storepass:env ALLOWANCE_JARSIGN_PASS -digestalg SHA-256 -sigalg SHA256withRSA -signedjar $temp $bundle upload
    if ($LASTEXITCODE) { throw 'Signing failed.' }
    & $jarsigner -verify -strict -keystore $store -storepass:env ALLOWANCE_JARSIGN_PASS $temp upload
    if ($LASTEXITCODE) { throw 'The signed bundle or upload certificate failed verification.' }
    [IO.File]::Move($temp, $final)
} finally {
    Remove-Item Env:ALLOWANCE_JARSIGN_PASS -ErrorAction SilentlyContinue
    $secure.Dispose()
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp }
}
Get-FileHash -Algorithm SHA256 -LiteralPath $final | Format-List
