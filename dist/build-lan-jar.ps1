<#
    Builds the single jar for the LAN / Windows-service package. MAINTAINER TOOL - never
    shipped to a client.

      .\dist\build-lan-jar.ps1               # build and drop it into dist\lan\resources\
      .\dist\build-lan-jar.ps1 -RunTests     # run the backend unit tests first
      .\dist\build-lan-jar.ps1 -NoBackup     # overwrite the old jar instead of keeping a .bak

    Steps: build the Angular bundle, copy it into backend\src\main\resources\static\ (gitignored)
    so Spring Boot serves the SPA, package the Spring Boot jar, check the web app is inside,
    and copy it to dist\lan\resources\orbixerp.jar with an orbixerp.jar.version.txt beside it
    (commit + build time - so you can tell what a client is running when they call).

    The old jar is kept as orbixerp.jar.bak-<timestamp>. Delete it before handing the folder
    over - it is ~90 MB.

    A running service holds the jar open: stop it before running this against a live install.
    Nothing here touches the database; a new jar picks up the .env already next to it.

    Requirements: Node/npm, Maven, JDK 21, git.
#>

[CmdletBinding()]
param(
    # Run `mvn test` (unit + ArchUnit gates, no Docker) before packaging.
    [switch] $RunTests,
    # Overwrite dist\lan\resources\orbixerp.jar without keeping a backup copy.
    [switch] $NoBackup
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$WebDir     = Join-Path $RepoRoot 'web'
$BackendDir = Join-Path $RepoRoot 'backend'
$StaticDir  = Join-Path $BackendDir 'src\main\resources\static'
$WebOut     = Join-Path $WebDir 'dist\web\browser'
$LanDir     = Join-Path $ScriptDir 'lan\resources'
$TargetJar  = Join-Path $LanDir 'orbixerp.jar'

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
foreach ($tool in 'npm', 'mvn', 'java', 'git') {
    if ($null -eq (Get-Command $tool -ErrorAction SilentlyContinue)) { Stop-WithError "$tool is not installed or not on PATH." }
}
Write-Ok 'npm, mvn, java, git found'

Push-Location $RepoRoot
try {
    $Branch = (& git rev-parse --abbrev-ref HEAD | Select-Object -First 1).Trim()
    $Commit = (& git rev-parse --short HEAD | Select-Object -First 1).Trim()
    $Dirty  = @(& git status --porcelain --untracked-files=no)
} finally { Pop-Location }
Write-Ok "building $Branch @ $Commit"
if ($Dirty.Count -gt 0) {
    # Allowed (you may be testing a local fix), but the jar then is not exactly $Commit.
    Write-Warn "the working tree has $($Dirty.Count) uncommitted change(s) - they WILL be in this jar"
    $Commit = "$Commit+uncommitted"
}

# ---------------------------------------------------------------------------
Write-Step 'Building the web app'
if (-not (Test-Path (Join-Path $WebDir 'node_modules'))) {
    Invoke-In $WebDir 'npm ci' { & npm ci }
}
Invoke-In $WebDir 'npm run build' { & npm run build }
if (-not (Test-Path (Join-Path $WebOut 'index.html'))) { Stop-WithError "The web build produced no index.html under $WebOut." }
Write-Ok 'web bundle built'

Write-Step 'Copying the web app into the backend'
# Clear first: the bundle's file names are content hashes, so stale chunks would pile up.
if (Test-Path $StaticDir) { Remove-Item -Recurse -Force $StaticDir }
New-Item -ItemType Directory -Force $StaticDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $WebOut '*') $StaticDir
Write-Ok "copied to $StaticDir"

# ---------------------------------------------------------------------------
if ($RunTests) {
    Write-Step 'Running backend unit tests'
    Invoke-In $BackendDir 'mvn test' { & mvn -B -q test }
    Write-Ok 'tests passed'
}

Write-Step 'Packaging the jar'
Invoke-In $BackendDir 'mvn package' { & mvn -B -q '-Dmaven.test.skip=true' package }
$Built = Get-ChildItem (Join-Path $BackendDir 'target') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $Built) { Stop-WithError 'mvn package produced no jar under backend\target.' }

# A jar without the SPA still boots but serves a blank page - catch that here, not at the client.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Built.FullName)
try {
    $hasIndex = $null -ne ($zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' } | Select-Object -First 1)
} finally { $zip.Dispose() }
if (-not $hasIndex) { Stop-WithError "$($Built.Name) does not contain the web app (static/index.html)." }
Write-Ok ("{0} ({1:N0} MB), web app inside" -f $Built.Name, ($Built.Length / 1MB))

# ---------------------------------------------------------------------------
Write-Step 'Placing it in the LAN package'
if (-not (Test-Path $LanDir)) {
    Write-Warn "$LanDir did not exist - created it. The service files (WinSW, .env, run\ scripts) are not in it."
    New-Item -ItemType Directory -Force $LanDir | Out-Null
}
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
try {
    if ((Test-Path $TargetJar) -and -not $NoBackup) {
        $Backup = "$TargetJar.bak-$Stamp"
        Move-Item -Force $TargetJar $Backup
        Write-Ok "old jar kept as $(Split-Path -Leaf $Backup) - delete it before handing over"
    }
    Copy-Item -Force $Built.FullName $TargetJar
} catch {
    Stop-WithError "Could not replace $TargetJar. If the OrbixERP service is running from this folder, stop it first (run\stop.cmd)."
}

$BuildTime = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
Set-Content -Encoding ascii -Path (Join-Path $LanDir 'orbixerp.jar.version.txt') -Value @(
    "commit: $Commit",
    "branch: $Branch",
    "built:  $BuildTime"
)
Write-Ok "$TargetJar"

Write-Host ''
Write-Host "Done. $Branch @ $Commit" -ForegroundColor Green
Write-Host 'To update a running install: stop the service, replace orbixerp.jar, start the service,'
Write-Host 'then Ctrl+F5 in the browser so it loads the new web app.'
