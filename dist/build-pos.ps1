<#
    Builds the OrbixPOS Windows release zip. MAINTAINER TOOL - never shipped to a client.

      .\dist\build-pos.ps1 -Notes .\notes-1.5.3.txt   # test, build, zip with release notes
      .\dist\build-pos.ps1 -SkipTests                 # skip `flutter test` (not for a release)
      .\dist\build-pos.ps1 -Force                     # overwrite an existing zip of this version

    Steps: read the version from pos_app\pubspec.yaml, run `flutter test`, run
    `flutter build windows --release`, check pos_app.exe is there and carries that version, then
    zip the Release folder with a READ-ME.txt into dist\OrbixPOS-<version>-windows.zip and print
    its SHA-256.

    One version, one zip. Every materially different build gets a NEW version - bump `version:` in
    pos_app\pubspec.yaml (e.g. 1.5.2+10 -> 1.5.3+11) before running this. The script refuses to
    overwrite an existing zip of the same version unless -Force, so two different builds can never
    be told apart only by their date.

    READ-ME.txt = the standard "To run" / "First run" sections, the build commit, then the contents
    of -Notes (the release-specific "New in <version>" / "Before rolling out" text, plain ASCII).
    Without -Notes the READ-ME has no release notes - fine for a test build, not for a client.

    It warns if the working tree has uncommitted changes: they are built into the zip, and the
    READ-ME then reads "<commit>+uncommitted" so the build is never mistaken for a clean one.

    Never builds with POS_ALLOW_INSECURE_TLS: a till shipped with it accepts any certificate.

    Requirements: Flutter (Windows desktop toolchain), git.
#>

[CmdletBinding()]
param(
    # Release-notes text file appended to READ-ME.txt ("New in ..." / "Before rolling out").
    [string] $Notes,
    # Skip `flutter test`. For a quick local build only - never for a release.
    [switch] $SkipTests,
    # Overwrite dist\OrbixPOS-<version>-windows.zip if it already exists.
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot   = Split-Path -Parent $ScriptDir
$PosDir     = Join-Path $RepoRoot 'pos_app'
$Pubspec    = Join-Path $PosDir 'pubspec.yaml'
$ReleaseDir = Join-Path $PosDir 'build\windows\x64\runner\Release'

function Write-Step { param([string]$m) Write-Host ''; Write-Host "==> $m" -ForegroundColor White }
function Write-Ok   { param([string]$m) Write-Host "  ok  $m" -ForegroundColor Green }
function Write-Warn { param([string]$m) Write-Host "  !!  $m" -ForegroundColor Yellow }
function Stop-WithError {
    param([string]$m)
    Write-Host ''; Write-Host 'ERROR' -ForegroundColor Red; Write-Host ''; Write-Host $m; Write-Host ''
    exit 1
}

# Run a native command in a directory; fail the build on a non-zero exit code.
function Invoke-In {
    param([string]$Dir, [string]$What, [scriptblock]$Command)
    Push-Location $Dir
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { Stop-WithError "$What failed (exit code $LASTEXITCODE). See the output above." }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
Write-Step 'Checking tools'
foreach ($tool in 'flutter', 'git') {
    if ($null -eq (Get-Command $tool -ErrorAction SilentlyContinue)) { Stop-WithError "$tool is not installed or not on PATH." }
}
Write-Ok 'flutter, git found'

$VersionLine = Select-String -Path $Pubspec -Pattern '^version:\s*(\S+)' | Select-Object -First 1
if ($null -eq $VersionLine) { Stop-WithError "No 'version:' line found in $Pubspec." }
$Version = $VersionLine.Matches[0].Groups[1].Value
$BuildName = ($Version -split '\+')[0]      # 1.5.3+11 -> 1.5.3 (what the exe is stamped with)
$ZipPath = Join-Path $ScriptDir "OrbixPOS-$Version-windows.zip"
Write-Ok "version $Version"

if ((Test-Path $ZipPath) -and -not $Force) {
    Stop-WithError ("$ZipPath already exists.`n`n" +
        "Bump 'version:' in pos_app\pubspec.yaml for a new build, or pass -Force to replace it.")
}

if ($Notes) {
    if (-not (Test-Path $Notes)) { Stop-WithError "Release notes file not found: $Notes" }
    $NotesText = Get-Content -Raw -Path $Notes
    # Keep the READ-ME plain ASCII: it is opened in Notepad on whatever code page the till has.
    if ($NotesText -match '[^\x00-\x7F]') {
        Stop-WithError "$Notes contains non-ASCII characters (e.g. curly quotes or dashes). Use plain ASCII."
    }
} else {
    Write-Warn 'no -Notes given - READ-ME.txt will have no release notes (fine for a test build only)'
    $NotesText = ''
}

Push-Location $RepoRoot
try {
    $Branch = (& git rev-parse --abbrev-ref HEAD | Select-Object -First 1).Trim()
    $Commit = (& git rev-parse --short HEAD | Select-Object -First 1).Trim()
    $Dirty  = @(& git status --porcelain --untracked-files=no -- pos_app)
} finally { Pop-Location }
Write-Ok "building $Branch @ $Commit"
if ($Dirty.Count -gt 0) {
    # Allowed (the version bump itself is usually uncommitted), but the build is then not exactly $Commit.
    Write-Warn "pos_app has $($Dirty.Count) uncommitted change(s) - they WILL be in this build"
    $Commit = "$Commit+uncommitted"
}

# ---------------------------------------------------------------------------
Write-Step 'Fetching packages'
Invoke-In $PosDir 'flutter pub get' { & flutter pub get }
Write-Ok 'packages ready'

if ($SkipTests) {
    Write-Warn 'tests skipped (-SkipTests) - do not ship this build'
} else {
    Write-Step 'Running the POS tests'
    Invoke-In $PosDir 'flutter test' { & flutter test }
    Write-Ok 'tests passed'
}

Write-Step 'Building the Windows release'
# Clear the old output so a file the new build no longer produces cannot ride along into the zip.
if (Test-Path $ReleaseDir) { Remove-Item -Recurse -Force $ReleaseDir }
# Deliberately no --dart-define: POS_ALLOW_INSECURE_TLS must never be on in a shipped till.
Invoke-In $PosDir 'flutter build windows' { & flutter build windows --release }

$Exe = Join-Path $ReleaseDir 'pos_app.exe'
if (-not (Test-Path $Exe)) { Stop-WithError "The build produced no pos_app.exe under $ReleaseDir." }
$ExeVersion = (Get-Item $Exe).VersionInfo.ProductVersion
if ($ExeVersion -and -not $ExeVersion.StartsWith($BuildName)) {
    Stop-WithError "pos_app.exe is stamped '$ExeVersion', not $Version. Run 'flutter clean' in pos_app and try again."
}
Write-Ok "pos_app.exe built (version $ExeVersion)"

# ---------------------------------------------------------------------------
Write-Step 'Packaging the zip'
$Staging = Join-Path ([IO.Path]::GetTempPath()) "orbixpos-$Version-$(Get-Date -Format 'yyyyMMddHHmmss')"
New-Item -ItemType Directory -Force $Staging | Out-Null
try {
    Copy-Item -Recurse -Force (Join-Path $ReleaseDir '*') $Staging

    $ReadMe = @"
OrbixPOS $Version (Windows x64)
Build: $Branch @ $Commit

To run
------
Unzip this folder anywhere (e.g. C:\OrbixPOS) and run pos_app.exe.
Keep every file together - pos_app.exe will not start on its own.
To upgrade, close OrbixPOS, replace the old folder's files with these, and
start it again. Your server setup and sign-in are kept.

First run
---------
Setup -> ERP server. Enter the scheme and host ONLY, with no trailing path:
  http://192.168.1.10:8080      (a server on your network)
  http://localhost:8080         (the ERP on this same machine)
Do NOT add /api/v1 - the app appends that itself.

Then sign in with the cashier account your administrator created. A cashier
needs an internal sales-agent record; the super-admin account cannot ring
sales.
"@
    if ($NotesText) { $ReadMe = $ReadMe + "`r`n`r`n" + $NotesText.TrimEnd() + "`r`n" }
    Set-Content -Encoding ascii -Path (Join-Path $Staging 'READ-ME.txt') -Value $ReadMe

    if (Test-Path $ZipPath) { Remove-Item -Force $ZipPath }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($Staging, $ZipPath)
} finally {
    Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue
}

$ZipItem = Get-Item $ZipPath
$Sha = (Get-FileHash -Algorithm SHA256 $ZipPath).Hash.ToLower()
Write-Ok ("{0} ({1:N1} MB)" -f $ZipItem.Name, ($ZipItem.Length / 1MB))
Write-Ok "SHA-256 $Sha"

Write-Host ''
Write-Host "Done. OrbixPOS $Version from $Branch @ $Commit" -ForegroundColor Green
Write-Host "Zip: $ZipPath"
Write-Host 'Before handing it over: unzip it on a clean folder, start pos_app.exe, and do a test sale + print.'
