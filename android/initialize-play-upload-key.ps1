# One-time setup on the owner's Windows signing machine. No Play account access.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$directory = Join-Path $env:LOCALAPPDATA 'AllowanceMeter/PlaySigning'
if (Test-Path -LiteralPath $directory) { throw "Signing directory already exists. No files were changed: $directory" }
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'Set JAVA_HOME to JDK 17 first.' }
[void][IO.Directory]::CreateDirectory($directory)
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner($sid)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $sid, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow'))
Set-Acl -LiteralPath $directory -AclObject $acl
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:ALLOWANCE_KEY_INIT_PASS = [Convert]::ToBase64String($bytes)
$store = Join-Path $directory 'upload.p12'
try {
    $secure = ConvertTo-SecureString -AsPlainText $env:ALLOWANCE_KEY_INIT_PASS -Force
    # Without -Key, Windows DPAPI binds this ciphertext to the current user.
    ConvertFrom-SecureString $secure | Set-Content -Encoding ascii -LiteralPath (Join-Path $directory 'password.dpapi')
    & $keytool -genkeypair -storetype PKCS12 -keystore $store -storepass:env ALLOWANCE_KEY_INIT_PASS -alias upload -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Allowance Meter Upload'
    if ($LASTEXITCODE) { throw 'Could not generate the upload key.' }
    & $keytool -exportcert -rfc -keystore $store -storepass:env ALLOWANCE_KEY_INIT_PASS -alias upload -file (Join-Path $directory 'upload-certificate.pem')
    if ($LASTEXITCODE) { throw 'Could not export the public certificate.' }
} finally {
    Remove-Item Env:ALLOWANCE_KEY_INIT_PASS -ErrorAction SilentlyContinue
    [Array]::Clear($bytes, 0, $bytes.Length)
    if ($secure) { $secure.Dispose() }
}
Write-Output "Created the Play upload key in $directory. Back up the PKCS12 file and its password securely; only the PEM certificate is public."
