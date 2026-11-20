<#
    Builds the Orbix App PWA - OrbixHQ for the browser, served by IIS at
    https://app.orbixsystems.app. MAINTAINER TOOL - never shipped to a client.

      double-click dist\build-pwa.cmd           # the easy way
      .\dist\build-pwa.ps1                       # tests, build, stamp, zip
      .\dist\build-pwa.ps1 -SkipTests            # quick local build only - never for a deploy
      .\dist\build-pwa.ps1 -Force                # overwrite an existing zip of this version

    WEB ONLY. It does not touch the Android build: it compiles the separate web
    entry point lib\main_web.dart, and build-hq.ps1 / the APKs are unaffected.

    Steps:
      1. version from mobile_exec\pubspec.yaml (same number as the APK);
      2. `flutter test` (unless -SkipTests);
      3. `flutter build web --release -t lib/main_web.dart --no-web-resources-cdn`
         with the generic Orbix brand renamed "Orbix App" (mark "O");
         no server address is baked in - the PWA uses its own origin, and IIS
         forwards /api/ to the ERP;
      4. trims files the dart2js/CanvasKit build never loads (debug symbols,
         the skwasm/wimp renderers, Flutter's own empty service worker);
      5. stamps web\sw.js with VERSION = <app version>-<content hash> and the
         precache list - so EVERY deploy that changes any file is a new
         version, and the browser offers "A new version is ready";
      6. zips build\web to dist\orbixapp\OrbixApp-<version>.zip with a .json
         record (version, commit, SHA-256). Zips are git-ignored.

    Deploy the zip with WINDOWS_EC2_DEPLOYMENT.md (repo root).
    Requirements: Flutter, git.
#>

[CmdletBinding()]
param(
    [switch] $SkipTests,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$AppDir    = Join-Path $RepoRoot 'mobile_exec'
$Pubspec   = Join-Path $AppDir 'pubspec.yaml'
$WebOut    = Join-Path $AppDir 'build\web'
$OutDir    = Join-Path $ScriptDir 'orbixapp'
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Write-Step { param([string]$m) Write-Host ''; Write-Host "==> $m" -ForegroundColor White }
function Write-Ok   { param([string]$m) Write-Host "  ok  $m" -ForegroundColor Green }
function Stop-WithError {
    param([string]$m)
    Write-Host ''; Write-Host 'ERROR' -ForegroundColor Red; Write-Host ''; Write-Host $m; Write-Host ''
    exit 1
}
function Invoke-In {
    param([string]$Dir, [string]$What, [scriptblock]$Command)
    Push-Location $Dir
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { Stop-WithError "$What failed (exit code $LASTEXITCODE). See the output above." }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------- 1. version
$verLine = Select-String -Path $Pubspec -Pattern '^version:\s*(\S+)' | Select-Object -First 1
if (-not $verLine) { Stop-WithError "No version: line in $Pubspec" }
$AppVersion = $verLine.Matches[0].Groups[1].Value          # e.g. 1.4.2+9
$FileVersion = $AppVersion -replace '\+', '-'               # e.g. 1.4.2-9
$Commit = (& git -C $RepoRoot rev-parse --short HEAD).Trim()
$Dirty = [bool](& git -C $RepoRoot status --porcelain -- mobile_exec)
Write-Step "Orbix App $AppVersion (commit $Commit$(if ($Dirty) { ', UNCOMMITTED changes' }))"

$Zip = Join-Path $OutDir "OrbixApp-$FileVersion.zip"
if ((Test-Path $Zip) -and -not $Force) {
    Stop-WithError "$Zip already exists. Bump the version in pubspec.yaml, or pass -Force."
}

# ---------------------------------------------------------------- 2. tests
if ($SkipTests) {
    Write-Host '  !!  tests skipped (-SkipTests) - do not deploy this build' -ForegroundColor Yellow
} else {
    Write-Step 'flutter test'
    Invoke-In $AppDir 'flutter test' { flutter test }
    # `flutter test` regenerates the Windows desktop plugin registrant; put it back.
    & git -C $RepoRoot checkout -- mobile_exec/windows/flutter 2>$null
    Write-Ok 'tests passed'
}

# ---------------------------------------------------------------- 3. build
Write-Step 'flutter build web'
$brandDefines = Join-Path $AppDir 'brands\orbix\generated\dart_defines.json'
if (-not (Test-Path $brandDefines)) { Stop-WithError "Missing $brandDefines (python mobile_exec\tool\brand.py gen orbix)" }
$defines = Get-Content $brandDefines -Raw -Encoding UTF8 | ConvertFrom-Json
$defines.BRAND_APP_NAME = 'Orbix App'
$defines.BRAND_MARK = 'O'
$defines | Add-Member -NotePropertyName HQ_HOST -NotePropertyValue '' -Force
$definesFile = Join-Path $AppDir 'build\pwa_dart_defines.json'
New-Item -ItemType Directory -Force (Split-Path $definesFile) | Out-Null
[IO.File]::WriteAllText($definesFile, ($defines | ConvertTo-Json), $Utf8NoBom)

# Always from clean: an incremental web build drops files that were in the
# previous build's output (seen: assets/, favicon.png and icons went missing).
if (Test-Path $WebOut) { Remove-Item -Recurse -Force $WebOut }

Invoke-In $AppDir 'flutter build web' {
    flutter build web --release -t lib/main_web.dart --no-web-resources-cdn `
        --no-wasm-dry-run "--dart-define-from-file=$definesFile"
}
Write-Ok "built $WebOut"

# ---------------------------------------------------------------- 4. trim
Write-Step 'trim files this build never loads'
$trim = @('flutter_service_worker.js', '.last_build_id')
foreach ($t in $trim) { Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $WebOut $t) }
Get-ChildItem -Path (Join-Path $WebOut 'canvaskit') -Recurse -File |
    Where-Object { $_.Name -like '*.symbols' -or $_.Name -like 'skwasm*' -or $_.Name -like 'wimp*' } |
    Remove-Item -Force
Write-Ok 'removed debug symbols, unused renderers and Flutter''s empty service worker'

# ---------------------------------------------------------------- 5. stamp sw.js
Write-Step 'stamp the service worker'
$swPath = Join-Path $WebOut 'sw.js'
$sw = [IO.File]::ReadAllText($swPath)
if ($sw -notmatch "'__ORBIX_VERSION__'") { Stop-WithError 'sw.js has no version placeholder - is web\sw.js the template?' }

$files = Get-ChildItem -Path $WebOut -Recurse -File | Sort-Object FullName
$rel = { param($f) $f.FullName.Substring($WebOut.Length + 1).Replace('\', '/') }

# Version = app version + hash of every file (the sw.js TEMPLATE included), so
# any change anywhere produces a new service worker and a new cache.
$sha = [System.Security.Cryptography.SHA256]::Create()
$sb = New-Object System.Text.StringBuilder
foreach ($f in $files) {
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash
    [void]$sb.Append((& $rel $f)).Append(':').Append($h).Append("`n")
}
$digest = ($sha.ComputeHash($Utf8NoBom.GetBytes($sb.ToString())) | ForEach-Object { $_.ToString('x2') }) -join ''
$Version = "$AppVersion-$($digest.Substring(0, 10))"

# Precache = the app shell. Left to runtime caching (fetched on first use):
# the CanvasKit engine (one of two variants per browser), the licence text and
# the PDF viewer. Never cached: sw.js itself, web.config (IIS refuses to serve
# it), version.json.
$precache = foreach ($f in $files) {
    $p = & $rel $f
    if ($p -eq 'sw.js' -or $p -eq 'web.config' -or $p -eq 'version.json') { continue }
    if ($p -like 'canvaskit/*' -or $p -like 'pdfjs/*' -or $p -eq 'assets/NOTICES') { continue }
    "./$p"
}
$precacheJson = '[' + (($precache | ForEach-Object { '"' + $_ + '"' }) -join ', ') + ']'
$sw = $sw.Replace("'__ORBIX_VERSION__'", "'$Version'")
$sw = $sw.Replace('/*__ORBIX_PRECACHE__*/ []', $precacheJson)
[IO.File]::WriteAllText($swPath, $sw, $Utf8NoBom)
$precacheBytes = ($files | Where-Object { $precache -contains ('./' + (& $rel $_)) } | Measure-Object Length -Sum).Sum
Write-Ok ("version {0}; precache {1} files, {2:N1} MB" -f $Version, @($precache).Count, ($precacheBytes / 1MB))

# ---------------------------------------------------------------- 6. zip
Write-Step 'package'
New-Item -ItemType Directory -Force $OutDir | Out-Null
if (Test-Path $Zip) { Remove-Item -Force $Zip }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($WebOut, $Zip, [IO.Compression.CompressionLevel]::Optimal, $false)
$zipHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Zip).Hash
$total = ($files | Measure-Object Length -Sum).Sum
$record = [ordered]@{
    app       = 'Orbix App (PWA)'
    version   = $AppVersion
    swVersion = $Version
    commit    = $Commit
    dirty     = $Dirty
    builtAt   = (Get-Date).ToString('s')
    site      = 'https://app.orbixsystems.app'
    sha256    = $zipHash
}
[IO.File]::WriteAllText(($Zip -replace '\.zip$', '.json'), ($record | ConvertTo-Json), $Utf8NoBom)
Write-Ok ("{0}  ({1:N1} MB site, sha256 {2}...)" -f $Zip, ($total / 1MB), $zipHash.Substring(0, 12))
Write-Host ''
Write-Host "Deploy: unzip into the IIS site folder - see WINDOWS_EC2_DEPLOYMENT.md" -ForegroundColor White
