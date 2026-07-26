[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot 'src'
$outputRoot = Join-Path $projectRoot 'bin'
$outputPath = Join-Path $outputRoot 'CodexUsageTray.exe'

$compilerCandidates = @(
    'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe',
    'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe'
)

$compilerPath = $compilerCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $compilerPath) {
    throw 'The .NET Framework C# compiler was not found. Install .NET Framework 4.8.'
}

$sourceFiles = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.cs' -File |
    Sort-Object Name

if (-not $sourceFiles) {
    throw "No C# sources were found under $sourceRoot."
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
}

$compilerArguments = @(
    '/nologo',
    '/target:winexe',
    '/platform:anycpu',
    '/optimize+',
    '/debug:pdbonly',
    '/warn:4',
    '/codepage:65001',
    "/out:$outputPath",
    '/reference:System.dll',
    '/reference:System.Core.dll',
    '/reference:System.Drawing.dll',
    '/reference:System.Windows.Forms.dll',
    '/reference:System.Web.Extensions.dll'
)

$compilerArguments += $sourceFiles.FullName

& $compilerPath @compilerArguments

if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed with exit code $LASTEXITCODE."
}

Write-Host "Built $outputPath"
