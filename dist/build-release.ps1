<#
    Builds a client distribution bundle. MAINTAINER TOOL - never shipped to a client.

      .\dist\build-release.ps1 -Version 1.0.0                # amd64 + arm64
      .\dist\build-release.ps1 -Version 1.0.0 -Arch amd64    # one architecture only
      .\dist\build-release.ps1 -RefreshDocs                  # after editing a guide

    The guides in dist\bundle\docs\ are committed as BOTH .md and .txt. The .txt are
    generated - edit the .md, run -RefreshDocs, and commit both. A release refuses to
    build if a committed .txt no longer matches its .md.

    Produces, under dist\release\:
      orbixerp-<version>-amd64\      the client bundle (compose files, scripts, docs, images)
      orbixerp-<version>-amd64.zip   the same thing, zipped, ready to hand over
      ... and the arm64 pair.

    The compiler runs ONCE, natively. app.jar is JVM bytecode plus a static web bundle and
    is identical on every architecture - only the JRE base image differs. Building the
    arm64 image is therefore a file copy onto an arm64 base, not an emulated compile.
    See the header of dist\Dockerfile.build.

    Requirements: Docker Desktop 4.x (buildx is included).
#>

[CmdletBinding()]
param(
    [string] $Version,
    [ValidateSet('amd64', 'arm64', 'both')] [string] $Arch = 'both',
    # Regenerate the committed dist\bundle\docs\*.txt after editing a guide, then exit.
    [switch] $RefreshDocs
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir

$ImageName     = 'orbixerp-api'
$PostgresImage = 'postgres:15-alpine'
$CaddyImage    = 'caddy:2-alpine'

function Write-Step { param([string]$m) Write-Host ''; Write-Host "==> $m" -ForegroundColor White }
function Write-Ok   { param([string]$m) Write-Host "  ok  $m" -ForegroundColor Green }
function Stop-WithError {
    param([string]$m)
    Write-Host ''; Write-Host 'ERROR' -ForegroundColor Red; Write-Host ''; Write-Host $m; Write-Host ''
    exit 1
}

# A moving tag in a client's compose file makes it impossible to know what they are
# running when they call for support. Releases are always an explicit version.
if (-not $RefreshDocs) {
    if ([string]::IsNullOrWhiteSpace($Version)) { Stop-WithError '-Version is required, e.g. -Version 1.0.0' }
    if ($Version -eq 'latest') { Stop-WithError "'latest' is not a valid release version. Use an explicit version such as 1.0.0." }
}

$Arches = if ($Arch -eq 'both') { @('amd64', 'arm64') } else { @($Arch) }

$BuildDate = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$BuildCommit = 'unknown'
try {
    Push-Location $RepoRoot
    $c = & git rev-parse --short HEAD
    if ($LASTEXITCODE -eq 0) { $BuildCommit = ($c | Select-Object -First 1).Trim() }
} catch { } finally { Pop-Location }

# ---------------------------------------------------------------------------
function Test-Tools {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) { Stop-WithError 'docker is not installed.' }
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker buildx version *> $null } catch { }
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'docker buildx is not available. Update Docker Desktop.' }
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker info *> $null } catch { }
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Docker is not running.' }
    $ErrorActionPreference = 'Stop'
}

# The PowerShell scripts must contain NOTHING but ASCII.
#
# Windows PowerShell 5.1 - still the default interpreter on every Windows client - reads a
# file without a byte-order mark as CP1252, not UTF-8. A UTF-8 em dash (E2 80 94) is then
# decoded as three characters ending in U+201D, a curly closing quote, which PowerShell
# accepts as a STRING DELIMITER. The result: one decorative dash in a comment silently
# breaks the installer's parsing on the client's machine while looking perfect here.
#
# Adding a BOM would also fix it, but ASCII-only survives every editor and encoding, so
# that is the rule and this gate is what keeps it true.
# Batch files are included for the same reason: a .cmd is interpreted under whatever OEM code
# page the machine happens to use, so a non-ASCII character renders as mojibake on one client
# and fine on another.
function Test-AsciiScripts {
    $bad = $false
    foreach ($f in (Get-ChildItem $ScriptDir -Recurse -Include '*.ps1', '*.cmd' -File)) {
        $offenders = [System.IO.File]::ReadAllBytes($f.FullName) | Where-Object { $_ -gt 127 }
        if ($offenders.Count -gt 0) {
            Write-Host "  $($f.Name) contains $($offenders.Count) non-ASCII byte(s)"
            $bad = $true
        }
    }
    if ($bad) {
        Stop-WithError "PowerShell scripts must be ASCII-only - see the explanation above this check`nin build-release.ps1. Replace the offending characters and build again."
    }
    Write-Ok 'PowerShell scripts are ASCII-only'
}

# The default stack name is declared in THREE independent places and they must agree.
#
# They did not, once: a rebrand updated the compose files and .env.example to "orbixerp" but
# missed a bare PowerShell string literal in the wizard, so the installer wrote
# ERP_STACK_NAME=erp into .env and produced containers called erp-db and erp-api - colliding
# with anything else on the machine using those names, which is the exact collision this
# setting exists to prevent. Pattern-matching across many files cannot be trusted to catch
# every category of match, so the build asserts the outcome instead of trusting the edit.
function Test-StackName {
    $bundle = Join-Path $ScriptDir 'bundle'
    $fromEnv = ''
    $m = Select-String -Path (Join-Path $bundle '.env.example') -Pattern '^ERP_STACK_NAME=(.+)$' | Select-Object -First 1
    if ($m) { $fromEnv = $m.Matches[0].Groups[1].Value.Trim() }

    $fromCompose = ''
    $m = Select-String -Path (Join-Path $bundle 'docker-compose.yml') -Pattern 'ERP_STACK_NAME:-([A-Za-z0-9_-]+)' | Select-Object -First 1
    if ($m) { $fromCompose = $m.Matches[0].Groups[1].Value }

    $fromWizard = ''
    $m = Select-String -Path (Join-Path $bundle 'setup-wizard.ps1') -Pattern "StackName\s*=\s*'([A-Za-z0-9_-]+)'" | Select-Object -First 1
    if ($m) { $fromWizard = $m.Matches[0].Groups[1].Value }

    # The remote wizard writes .env on a SERVER, where a name collision is even less welcome
    # than on a desktop - so it is held to the same rule as its local sibling.
    $fromRemote = ''
    $m = Select-String -Path (Join-Path $bundle 'remote-setup-wizard.ps1') -Pattern "StackName\s*=\s*'([A-Za-z0-9_-]+)'" | Select-Object -First 1
    if ($m) { $fromRemote = $m.Matches[0].Groups[1].Value }

    if (-not $fromEnv -or -not $fromCompose -or -not $fromWizard -or -not $fromRemote) {
        Stop-WithError "Could not read the default stack name from all four sources.`n  .env.example             '$fromEnv'`n  docker-compose.yml       '$fromCompose'`n  setup-wizard.ps1         '$fromWizard'`n  remote-setup-wizard.ps1  '$fromRemote'"
    }
    if ($fromEnv -ne $fromCompose -or $fromEnv -ne $fromWizard -or $fromEnv -ne $fromRemote) {
        Stop-WithError @"
The default stack name disagrees between files:
  .env.example             $fromEnv
  docker-compose.yml       $fromCompose
  setup-wizard.ps1         $fromWizard
  remote-setup-wizard.ps1  $fromRemote

All four must match, or an installer writes one name into .env while compose defaults to
another - and the client ends up with wrongly-named containers that collide on their machine.
"@
    }
    Write-Ok "Stack name is consistent everywhere ($fromEnv)"
}

# ---------------------------------------------------------------------------
# Compile once
# ---------------------------------------------------------------------------
function Build-Jar {
    Write-Step 'Compiling the application (web bundle + API) - this is the slow part'
    $script:BuildDir = Join-Path $RepoRoot 'dist\build'
    if (Test-Path $script:BuildDir) { Remove-Item $script:BuildDir -Recurse -Force }
    New-Item -ItemType Directory -Path $script:BuildDir -Force | Out-Null

    $dest = ($script:BuildDir -replace '\\', '/')
    & docker buildx build `
        --target export `
        --output "type=local,dest=$dest" `
        -f (Join-Path $ScriptDir 'Dockerfile.build') `
        $RepoRoot | Out-Host
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'The application build failed. The compiler output is above.' }

    $jar = Join-Path $script:BuildDir 'app.jar'
    if (-not (Test-Path $jar)) { Stop-WithError 'The build finished but produced no app.jar.' }
    Write-Ok "app.jar built ($([math]::Round((Get-Item $jar).Length / 1MB, 1)) MB)"
}

# ---------------------------------------------------------------------------
# Package one architecture
# ---------------------------------------------------------------------------
function Build-Bundle {
    param([string]$TargetArch)

    $out = Join-Path $RepoRoot "dist\release\orbixerp-$Version-$TargetArch"
    Write-Step "Packaging for $TargetArch"
    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    New-Item -ItemType Directory -Path (Join-Path $out 'images') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $out 'docs')   -Force | Out-Null

    # --- the application image ---------------------------------------------
    # `--output type=docker,dest=` writes a loadable archive straight to disk, so the
    # foreign-architecture image never has to enter the local image store.
    Write-Host "  building ${ImageName}:${Version} ($TargetArch)"
    $imgTar = Join-Path $out "images\$ImageName-$Version-$TargetArch.tar"
    & docker buildx build `
        --platform "linux/$TargetArch" `
        --build-arg "ERP_VERSION=$Version" `
        --build-arg "BUILD_DATE=$BuildDate" `
        -t "${ImageName}:${Version}" `
        --output "type=docker,dest=$($imgTar -replace '\\','/')" `
        -f (Join-Path $ScriptDir 'Dockerfile.runtime') `
        $script:BuildDir | Out-Host
    if ($LASTEXITCODE -ne 0) { Stop-WithError "Could not build the $TargetArch image." }

    # --- third-party images -------------------------------------------------
    # PostgreSQL ships even when the client uses their own database: backup and restore
    # run pg_dump/pg_restore from this image in both modes.
    # Caddy ships so the optional HTTPS overlay also works without internet access.
    Save-ThirdParty -TargetArch $TargetArch -Out $out -Image $PostgresImage -Name 'postgres-15-alpine'
    Save-ThirdParty -TargetArch $TargetArch -Out $out -Image $CaddyImage    -Name 'caddy-2-alpine'

    Write-Host '  compressing images'
    Get-ChildItem (Join-Path $out 'images') -Filter '*.tar' -File | ForEach-Object { Compress-Gzip $_.FullName }

    Copy-BundleFiles -Out $out -TargetArch $TargetArch
    New-TextDocs     -Out $out
    Write-Metadata   -Out $out -TargetArch $TargetArch
    Write-Checksums  -Out $out
    New-BundleArchive -Out $out

    $size = [math]::Round(((Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 0)
    Write-Ok "orbixerp-$Version-$TargetArch ready ($size MB)"
}

function Save-ThirdParty {
    param([string]$TargetArch, [string]$Out, [string]$Image, [string]$Name)
    Write-Host "  fetching $Image ($TargetArch)"
    & docker pull --platform "linux/$TargetArch" -q $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-WithError "Could not pull $Image for $TargetArch." }

    # --platform on the SAVE is essential, not decorative. Once both architectures have been
    # pulled (which happens the moment you build both bundles), one tag holds several platform
    # variants, and a bare `docker save` exports every one of them - inflating a bundle by
    # ~100 MB with layers the client can never run. Pin the export to the one we want.
    & docker save --platform "linux/$TargetArch" $Image -o (Join-Path $Out "images\$Name-$TargetArch.tar") | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-WithError "Could not save $Image for $TargetArch." }

    # Prove what came out. A wrong-architecture Postgres fails on the client with only
    # "exec format error" to go on, days after the handover.
    $got = & docker image inspect --format '{{.Architecture}}' $Image
    if (($got | Select-Object -First 1).Trim() -ne $TargetArch) {
        Stop-WithError "Pulled $Image but Docker reports architecture '$got', expected '$TargetArch'."
    }
}

# gzip is not a Windows command; .NET provides the same format, which `docker load`
# reads directly.
function Compress-Gzip {
    param([string]$Path)
    $in = [System.IO.File]::OpenRead($Path)
    try {
        $outStream = [System.IO.File]::Create("$Path.gz")
        try {
            $gz = New-Object System.IO.Compression.GZipStream($outStream, [System.IO.Compression.CompressionMode]::Compress)
            try { $in.CopyTo($gz) } finally { $gz.Dispose() }
        } finally { $outStream.Dispose() }
    } finally { $in.Dispose() }
    Remove-Item $Path -Force
}

function Copy-BundleFiles {
    param([string]$Out, [string]$TargetArch)
    Write-Host '  assembling bundle files'
    $src = Join-Path $ScriptDir 'bundle'

    foreach ($f in @('docker-compose.yml', 'docker-compose.db-docker.yml', 'docker-compose.db-host.yml',
                     'docker-compose.tls.yml', 'Caddyfile', 'orbixerp.sh', 'orbixerp.ps1',
                     'install.sh', 'install.ps1', 'Setup.cmd', 'setup-wizard.ps1',
                     'Install.cmd', 'OrbixERP.cmd', 'Remote-Setup.cmd', 'remote-setup-wizard.ps1',
                     'LICENSE.txt')) {
        $from = Join-Path $src $f
        if (-not (Test-Path $from)) { Stop-WithError "Missing bundle file: $f" }
        Copy-Item $from (Join-Path $Out $f) -Force
    }

    # Batch launchers must carry CRLF endings - a .cmd with bare LF misbehaves around
    # labels and goto.
    foreach ($f in @('Setup.cmd', 'Install.cmd', 'OrbixERP.cmd', 'Remote-Setup.cmd')) {
        $p = Join-Path $Out $f
        $t = (([System.IO.File]::ReadAllText($p) -split "`r?`n") -join "`r`n")
        [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
    }
    Copy-Item (Join-Path $src 'docs\*.md') (Join-Path $Out 'docs') -Force

    # Placeholders replaced so the client's .env needs no hand-editing to match the build.
    # Written with LF endings - see the note in orbixerp.ps1 about carriage returns in .env.
    $envText = [System.IO.File]::ReadAllText((Join-Path $src '.env.example'))
    $envText = $envText.Replace('__ERP_VERSION__', $Version).Replace('__BUNDLE_ARCH__', $TargetArch)
    $envText = ($envText -split "`r?`n") -join "`n"
    [System.IO.File]::WriteAllText((Join-Path $Out '.env.example'), $envText, (New-Object System.Text.UTF8Encoding($false)))

    # The shell scripts must also carry LF endings - a CRLF shebang line makes Linux
    # report the baffling "bad interpreter: /usr/bin/env bash^M".
    foreach ($f in @('orbixerp.sh', 'install.sh')) {
        $p = Join-Path $Out $f
        $t = ([System.IO.File]::ReadAllText($p) -split "`r?`n") -join "`n"
        [System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))
    }

    # Empty working folders, so a first-time client sees where things will go.
    New-Item -ItemType Directory -Path (Join-Path $Out 'backups')     -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Out 'secrets\jwt') -Force | Out-Null
    Set-Content -Path (Join-Path $Out 'backups\README.txt') -Encoding utf8 `
        -Value 'Database backups written by "erp backup" appear here.'
    Set-Content -Path (Join-Path $Out 'secrets\README.txt') -Encoding utf8 `
        -Value 'Signing keys generated by the installer appear here. Keep them private and backed up.'
}

# Plain-text copies of the guides, generated from the Markdown so the two cannot drift.
# They exist because a client on Windows can double-click a .txt and read it, whereas a .md
# opens in something unhelpful or not at all.
#
# The conversion runs in a container rather than on the host: dist\md2txt.js is the single
# implementation shared with build-release.sh, so the Windows and Linux release paths cannot
# produce different output, and no node installation is required to cut a release.
function Invoke-Md2Txt {
    param([string]$Dir)
    # Copied in rather than bind-mounted separately: one mount, and no single-file bind mount
    # to behave differently across platforms.
    $helper = Join-Path $Dir '.md2txt.js'
    Copy-Item (Join-Path $ScriptDir 'md2txt.js') $helper -Force
    & docker run --rm -v "$($Dir -replace '\\','/'):/docs" node:20-alpine node /docs/.md2txt.js /docs | Out-Host
    $code = $LASTEXITCODE
    Remove-Item $helper -Force -ErrorAction SilentlyContinue
    return $code
}

# -RefreshDocs: regenerate the committed .txt in dist\bundle\docs\ after editing a guide.
function Update-SourceDocs {
    Write-Step 'Regenerating dist\bundle\docs\*.txt from the Markdown'
    if ((Invoke-Md2Txt -Dir (Join-Path $ScriptDir 'bundle\docs')) -ne 0) {
        Stop-WithError 'Could not regenerate the plain-text guides.'
    }
    Write-Ok 'Done. Commit the .txt files alongside the .md you changed.'
}

# The .txt are committed so they are visible and reviewable in the repository, but they are
# GENERATED - so the build regenerates them and refuses to ship if the committed copies do
# not match. Without this gate, editing a .md and forgetting to regenerate would quietly hand
# a client documentation that contradicts itself.
function New-TextDocs {
    param([string]$Out)
    Write-Host '  generating plain-text copies of the guides'
    $docs = Join-Path $Out 'docs'
    if ((Invoke-Md2Txt -Dir $docs) -ne 0) { Stop-WithError 'Could not generate the plain-text guides.' }

    $stale = @()
    foreach ($txt in (Get-ChildItem $docs -Filter '*.txt' -File)) {
        $committed = Join-Path $ScriptDir "bundle\docs\$($txt.Name)"
        if (-not (Test-Path $committed)) { $stale += $txt.Name; continue }
        $a = (Get-FileHash $txt.FullName -Algorithm SHA256).Hash
        $b = (Get-FileHash $committed    -Algorithm SHA256).Hash
        if ($a -ne $b) { $stale += $txt.Name }
    }

    if ($stale.Count -gt 0) {
        Stop-WithError @"
The committed plain-text guides are out of date: $($stale -join ', ')

A .md guide was edited without regenerating its .txt. Run:

    .\dist\build-release.ps1 -RefreshDocs

then commit the regenerated .txt files and build again.
"@
    }
}

function Write-Metadata {
    param([string]$Out, [string]$TargetArch)
    $version = @(
        "ERP_VERSION=$Version"
        "BUNDLE_ARCH=$TargetArch"
        "BUILD_DATE=$BuildDate"
        "BUILD_COMMIT=$BuildCommit"
    )
    [System.IO.File]::WriteAllText((Join-Path $Out 'VERSION'), (($version -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

    $notes = @"
# OrbixERP $Version

Released $BuildDate.

## Before you update

``erp update`` takes a database backup automatically and stops if that backup fails.
Keep the backup it names: because database changes only run forwards, restoring that
backup is the only way to return to your previous version.

## Changes in this release

_To be completed for each release._
"@
    [System.IO.File]::WriteAllText((Join-Path $Out 'RELEASE-NOTES.md'), $notes, (New-Object System.Text.UTF8Encoding($false)))
}

function Write-Checksums {
    param([string]$Out)
    Write-Host '  writing checksums'
    # A client can verify a handover arrived intact, and support can confirm which build is
    # on a machine without relying on what anyone remembers installing. The format matches
    # `sha256sum -c` so it is verifiable on Linux too.
    $lines = @(
        "# OrbixERP $Version ($BuildDate) - SHA-256"
        '# Verify on Linux/macOS:  sha256sum -c CHECKSUMS.txt'
        '# Verify on Windows:      Get-FileHash <file> -Algorithm SHA256'
        ''
    )
    Get-ChildItem $Out -Recurse -File | Where-Object { $_.Name -ne 'CHECKSUMS.txt' } | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($Out.Length + 1) -replace '\\', '/'
        $lines += "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  ./$rel"
    }
    [System.IO.File]::WriteAllText((Join-Path $Out 'CHECKSUMS.txt'), (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

function New-BundleArchive {
    param([string]$Out)
    Write-Host '  creating archive'
    $zip = "$Out.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    # ZipFile rather than Compress-Archive: much faster on a folder of this size.
    # Fastest, not Optimal - the image tarballs are already gzip-compressed, so squeezing
    # them again costs minutes and saves almost nothing.
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $Out, $zip, [System.IO.Compression.CompressionLevel]::Fastest, $true)
}

# ---------------------------------------------------------------------------
Test-Tools

if ($RefreshDocs) { Update-SourceDocs; exit 0 }

Test-AsciiScripts
Test-StackName

Write-Host ''
Write-Host '  OrbixERP release build' -ForegroundColor White
Write-Host "  version      $Version"
Write-Host "  architecture $($Arches -join ', ')"
Write-Host "  commit       $BuildCommit"

Build-Jar
foreach ($a in $Arches) { Build-Bundle -TargetArch $a }

Write-Step 'Done'
Get-ChildItem (Join-Path $RepoRoot 'dist\release') | ForEach-Object { Write-Host "  $($_.Name)" }
Write-Host ''
Write-Host 'Hand the .zip to the client along with its CHECKSUMS.txt.'
Write-Host 'They run install.ps1 (Windows) or install.sh (Linux/macOS). Nothing else is needed.'
Write-Host ''
