<#
    OrbixERP - installer (Windows).
    Linux / macOS users: use install.sh instead.

      .\install.ps1              guided install - asks a few questions
      .\install.ps1 -Defaults    unattended install using the values already in .env

    If Windows refuses to run this file, start it like this:
      powershell -ExecutionPolicy Bypass -File .\install.ps1

    Safe to run more than once: an existing .env is never overwritten, existing signing
    keys are never regenerated, and an existing database is never touched.

    What it does, in order:
      1. checks this bundle matches your machine and that Docker Desktop is usable
      2. loads the application images from images\ (no internet required)
      3. creates .env and generates the passwords and signing keys
      4. checks the network port is free and, in `host` database mode, that your
         database is reachable, the credentials work, and it is safe to install into
      5. starts everything and waits until it is genuinely ready
#>

[CmdletBinding()]
param([switch] $Defaults)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$EnvFile       = Join-Path $ScriptDir '.env'
$EnvTemplate   = Join-Path $ScriptDir '.env.example'
$VersionFile   = Join-Path $ScriptDir 'VERSION'
$PostgresImage = 'postgres:15-alpine'

function Write-Step { param([string]$m) Write-Host ''; Write-Host "==> $m" -ForegroundColor White }
function Write-Ok   { param([string]$m) Write-Host "  ok  $m" -ForegroundColor Green }
function Write-Warn { param([string]$m) Write-Host "warn  $m" -ForegroundColor Yellow }
function Stop-WithError {
    param([string]$m)
    Write-Host ''
    Write-Host 'INSTALLATION STOPPED' -ForegroundColor Red
    Write-Host ''
    Write-Host $m
    Write-Host ''
    exit 1
}

# ---------------------------------------------------------------------------
# .env access. See the notes in orbixerp.ps1 on why values are matched line by line and
# why the file is always written with LF endings and no byte-order mark.
# ---------------------------------------------------------------------------
function Get-EnvValue {
    param([string]$Key, [string]$Default = '')
    if (-not (Test-Path $EnvFile)) { return $Default }
    $match = Select-String -Path $EnvFile -Pattern "^\s*$([regex]::Escape($Key))=" -ErrorAction SilentlyContinue |
             Select-Object -Last 1
    if ($null -eq $match) { return $Default }
    $value = ($match.Line -replace "^\s*$([regex]::Escape($Key))=", '').Trim("`r").Trim('"').Trim("'")
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

function Write-LfFile {
    param([string]$Path, [string[]]$Lines)
    $text = ($Lines -join "`n")
    if (-not $text.EndsWith("`n")) { $text += "`n" }
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Set-EnvValue {
    param([string]$Key, [string]$Value)
    $lines = @()
    if (Test-Path $EnvFile) { $lines = [System.IO.File]::ReadAllText($EnvFile) -split "`r?`n" }
    $replaced = $false
    $out = foreach ($line in $lines) {
        if (-not $replaced -and $line -match "^\s*$([regex]::Escape($Key))=") { $replaced = $true; "$Key=$Value" }
        else { $line }
    }
    if (-not $replaced) { $out = @($out) + "$Key=$Value" }
    Write-LfFile -Path $EnvFile -Lines $out
}

function Get-Meta {
    param([string]$Key)
    if (-not (Test-Path $VersionFile)) { return '' }
    foreach ($line in (Get-Content $VersionFile)) {
        if ($line -match "^$([regex]::Escape($Key))=(.*)$") { return $matches[1].Trim("`r") }
    }
    return ''
}

function Read-Answer {
    param([string]$Prompt, [string]$Default)
    if ($Defaults) { return $Default }
    $answer = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer
}

function Get-ImageRef {
    $img = Get-EnvValue 'ERP_IMAGE' 'orbixerp-api'
    $ver = Get-EnvValue 'ERP_VERSION' (Get-Meta 'ERP_VERSION')
    return "${img}:${ver}"
}

# ---------------------------------------------------------------------------
# 1. Environment checks
# ---------------------------------------------------------------------------
function Test-Architecture {
    $bundle = Get-Meta 'BUNDLE_ARCH'
    if ([string]::IsNullOrWhiteSpace($bundle)) { return }
    $hostArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
    # Caught here deliberately. Docker's own failure for a wrong-architecture image is
    # "exec format error", which tells a client nothing actionable.
    if ($bundle -ne $hostArch) {
        Stop-WithError "This installation bundle is built for $bundle processors,`nbut this machine is $hostArch.`n`nAsk your supplier for the $hostArch bundle. Nothing has been changed."
    }
    Write-Ok "Processor architecture: $hostArch"
}

function Test-Docker {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithError @"
Docker Desktop is not installed on this machine (or not available to this user).

  1. Download Docker Desktop for Windows from https://docker.com
  2. Install it, choosing the WSL 2 backend when asked
  3. Start Docker Desktop and wait for it to report 'Engine running'
  4. Run this installer again
"@
    }
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker info *> $null } catch { }
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError @"
Docker is installed but is not running.

Start Docker Desktop from the Start menu, wait until the whale icon in the system
tray stops animating and reports 'Engine running', then run this installer again.
"@
    }
    $ErrorActionPreference = 'Stop'
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker compose version *> $null } catch { }
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "Your Docker installation is too old - it does not have the 'docker compose' command.`nUpgrade Docker Desktop to version 4 or newer."
    }
    $ErrorActionPreference = 'Stop'
    $v = & docker version --format '{{.Server.Version}}'
    Write-Ok "Docker $v is running"
}

# ---------------------------------------------------------------------------
# 2. Images
# ---------------------------------------------------------------------------
function Import-Images {
    Write-Step 'Loading application images'
    $dir = Join-Path $ScriptDir 'images'
    $files = @()
    if (Test-Path $dir) {
        $files = Get-ChildItem $dir -File | Where-Object { $_.Name -like '*.tar' -or $_.Name -like '*.tar.gz' }
    }
    if ($files.Count -eq 0) {
        Stop-WithError @"
No image files were found in $dir

The bundle appears to be incomplete. It should contain the application image,
the PostgreSQL image and the Caddy image as .tar.gz files.
"@
    }
    foreach ($f in $files) {
        Write-Host "  $($f.Name)"
        # `docker load` decompresses gzip itself - no need to unzip first.
        & docker load -i $f.FullName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Stop-WithError "Could not load $($f.Name).`nThe file may be incomplete - check it against CHECKSUMS.txt and ask for a fresh copy if it does not match."
        }
    }
    Write-Ok "$($files.Count) image(s) loaded"
}

# ---------------------------------------------------------------------------
# 3. Configuration
# ---------------------------------------------------------------------------
# An installed system records its version in .env; the bundle records its own in VERSION.
# If a NEWER bundle's installer is run over an existing installation, those disagree - and
# quietly rewriting .env would perform an upgrade with no safety backup, which is exactly
# what `erp update` exists to prevent. So this stops instead and points at the right command.
function Test-VersionMatch {
    $installed = Get-EnvValue 'ERP_VERSION'
    $bundle    = Get-Meta 'ERP_VERSION'
    if ([string]::IsNullOrWhiteSpace($bundle)) { return }

    if ([string]::IsNullOrWhiteSpace($installed) -or $installed -eq '__ERP_VERSION__') {
        Set-EnvValue 'ERP_VERSION' $bundle
        return
    }
    if ($installed -eq $bundle) { return }

    Stop-WithError @"
This bundle is version $bundle, but version $installed is already installed here.

Upgrading is not the installer's job, because an upgrade must take a database backup
first. Use the update command instead, from your existing installation:

    .\orbixerp.ps1 update "$ScriptDir"

It backs up, upgrades, and tells you how to undo it. Nothing has been changed.
"@
}

function New-EnvFile {
    Write-Step 'Configuration'
    if (Test-Path $EnvFile) {
        Test-VersionMatch
        Write-Ok 'Using the existing .env (your settings are kept - nothing was overwritten)'
        return
    }
    if (-not (Test-Path $EnvTemplate)) { Stop-WithError 'The configuration template .env.example is missing from this bundle.' }

    # Copied through the LF writer rather than with Copy-Item: the template must not pick
    # up Windows line endings on its way to becoming .env.
    Write-LfFile -Path $EnvFile -Lines ([System.IO.File]::ReadAllText($EnvTemplate) -split "`r?`n")
    Write-Ok 'Created .env from the template'
    if (-not $Defaults) { Set-InteractiveConfig }
}

function Set-InteractiveConfig {
    Write-Host ''
    Write-Host 'A few questions. Press Enter to accept the value in brackets.' -ForegroundColor DarkGray
    Write-Host ''

    $mode = ''
    while ($true) {
        $mode = Read-Answer "Database - type 'docker' to let us run it for you, or 'host' to use your own PostgreSQL server" (Get-EnvValue 'ERP_DB_MODE' 'docker')
        if ($mode -eq 'docker' -or $mode -eq 'host') { break }
        Write-Warn "Please answer 'docker' or 'host'."
    }
    Set-EnvValue 'ERP_DB_MODE' $mode

    if ($mode -eq 'host') {
        Write-Host ''
        Write-Host 'Your PostgreSQL server must already have an EMPTY database and a login role' -ForegroundColor DarkGray
        Write-Host 'that owns it. docs\HOST-DB-SETUP.md has the exact commands.' -ForegroundColor DarkGray
        Write-Host ''
        Set-EnvValue 'ERP_DB_HOST' (Read-Answer '  Database server address (use host.docker.internal if it is on this machine)' (Get-EnvValue 'ERP_DB_HOST' 'host.docker.internal'))
        Set-EnvValue 'ERP_DB_PORT' (Read-Answer '  Port' (Get-EnvValue 'ERP_DB_PORT' '5432'))
        Set-EnvValue 'ERP_DB_NAME' (Read-Answer '  Database name' (Get-EnvValue 'ERP_DB_NAME' 'erp'))
        Set-EnvValue 'ERP_DB_USER' (Read-Answer '  Login role' (Get-EnvValue 'ERP_DB_USER' 'erp'))
        $pw = ''
        while ([string]::IsNullOrWhiteSpace($pw)) {
            $secure = Read-Host '  Password for that role' -AsSecureString
            $bstr   = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
            if ([string]::IsNullOrWhiteSpace($pw)) { Write-Warn '  A password is required in host mode.' }
        }
        Set-EnvValue 'ERP_DB_PASSWORD' $pw
    }

    Write-Host ''
    Set-EnvValue 'ERP_HTTP_PORT'              (Read-Answer 'Port for users to reach the system in their browser' (Get-EnvValue 'ERP_HTTP_PORT' '8080'))
    Set-EnvValue 'ERP_BOOTSTRAP_ORG_NAME'     (Read-Answer 'Organisation name' (Get-EnvValue 'ERP_BOOTSTRAP_ORG_NAME' 'My Organisation'))
    Set-EnvValue 'ERP_BOOTSTRAP_COMPANY_NAME' (Read-Answer 'Company name' (Get-EnvValue 'ERP_BOOTSTRAP_COMPANY_NAME' 'My Company'))
    Set-EnvValue 'ERP_BOOTSTRAP_BRANCH_NAME'  (Read-Answer 'Main branch / location name' (Get-EnvValue 'ERP_BOOTSTRAP_BRANCH_NAME' 'Head Office'))
    $tz = Read-Answer 'Timezone' (Get-EnvValue 'ERP_TIME_ZONE' 'Africa/Dar_es_Salaam')
    Set-EnvValue 'ERP_TIME_ZONE' $tz
    Set-EnvValue 'ERP_BOOTSTRAP_TIME_ZONE' $tz
    $cur = Read-Answer 'Currency the accounts are kept in' (Get-EnvValue 'ERP_BOOTSTRAP_CURRENCY_BASE' 'TZS')
    Set-EnvValue 'ERP_BOOTSTRAP_CURRENCY_BASE' $cur
    Set-EnvValue 'ERP_BOOTSTRAP_CURRENCY_DEFAULT' $cur
}

# Random secrets are generated by openssl INSIDE the application image just loaded.
# That is deliberate: identical behaviour on every platform, no tooling needed on the
# host (Windows has no openssl) and no internet connection.
function New-Secret {
    param([int]$Length = 24)
    $raw = & docker run --rm --entrypoint openssl (Get-ImageRef) rand -base64 64
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Could not generate a password. The application image may not have loaded correctly.' }
    $clean = ($raw -join '') -replace '[^A-Za-z0-9]', ''
    if ($clean.Length -lt $Length) { Stop-WithError 'Password generation returned too little data. Please run the installer again.' }
    return $clean.Substring(0, $Length)
}

function Set-Secrets {
    Write-Step 'Passwords'

    if ((Get-EnvValue 'ERP_DB_MODE' 'docker') -eq 'docker' -and [string]::IsNullOrWhiteSpace((Get-EnvValue 'ERP_DB_PASSWORD'))) {
        Set-EnvValue 'ERP_DB_PASSWORD' (New-Secret 32)
        Write-Ok 'Generated the database password (stored in .env)'
    }

    if ([string]::IsNullOrWhiteSpace((Get-EnvValue 'ERP_DB_PASSWORD'))) {
        Stop-WithError "ERP_DB_PASSWORD is empty in .env. In host mode this must be the password`nof the login role on your own PostgreSQL server."
    }

    if ([string]::IsNullOrWhiteSpace((Get-EnvValue 'ERP_BOOTSTRAP_ADMIN_PASSWORD'))) {
        Set-EnvValue 'ERP_BOOTSTRAP_ADMIN_PASSWORD' (New-Secret 20)
        Write-Ok 'Generated the first administrator password (shown at the end, and stored in .env)'
    }
}

function Set-JwtKeys {
    Write-Step 'Security keys'
    $dir = Join-Path $ScriptDir 'secrets\jwt'
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    if ((Test-Path (Join-Path $dir 'private.pem')) -and (Test-Path (Join-Path $dir 'public.pem'))) {
        # Never silently regenerate: new keys invalidate every session token and log every
        # user out, which is alarming and unexplained if it happens by itself.
        Write-Ok 'Existing signing keys found - keeping them'
        return
    }

    # --user 0 so the container may write into the mounted folder and then hand the files
    # to uid 10001, the non-root user the application runs as. On Windows the ownership
    # change is a no-op, but the same command must work unchanged on Linux.
    $script = 'set -e; ' +
              'openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /keys/private.pem 2>/dev/null; ' +
              'openssl pkey -pubout -in /keys/private.pem -out /keys/public.pem; ' +
              'chown 10001:10001 /keys/private.pem /keys/public.pem; ' +
              'chmod 600 /keys/private.pem; chmod 644 /keys/public.pem'
    & docker run --rm --user 0 -v "${dir}:/keys" --entrypoint sh (Get-ImageRef) -c $script | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Could not generate the security keys.' }

    Write-Ok 'Generated a signing key pair in secrets\jwt\'
    Write-Warn 'Back up secrets\jwt\ together with .env. Losing these keys logs every user out.'
}

# ---------------------------------------------------------------------------
# 4. Preflight
# ---------------------------------------------------------------------------
function Test-Port {
    $port = [int](Get-EnvValue 'ERP_HTTP_PORT' '8080')
    # Anything answering on the port means another program already owns it. Better to say
    # so now than to let Docker fail with "port is already allocated" later.
    $client = New-Object System.Net.Sockets.TcpClient
    $inUse = $false
    try {
        $async = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne(1000, $false) -and $client.Connected) { $inUse = $true }
    } catch { $inUse = $false } finally { $client.Close() }

    if ($inUse) {
        Stop-WithError @"
Port $port on this machine is already in use by another program.

Either stop that program, or pick a different port by editing this line in .env:
    ERP_HTTP_PORT=$port
and running .\install.ps1 again.

(On Windows, ports 80 and 8080 are often taken by IIS or another web application.)
"@
    }
    Write-Ok "Port $port is free"
}

function Test-HostDatabase {
    if ((Get-EnvValue 'ERP_DB_MODE' 'docker') -ne 'host') { return }

    $h = Get-EnvValue 'ERP_DB_HOST' 'host.docker.internal'
    $p = Get-EnvValue 'ERP_DB_PORT' '5432'
    $d = Get-EnvValue 'ERP_DB_NAME' 'erp'
    $u = Get-EnvValue 'ERP_DB_USER' 'erp'
    $pw = Get-EnvValue 'ERP_DB_PASSWORD'

    Write-Step "Checking your PostgreSQL server at ${h}:${p}"

    # Reachability. Without this check an unreachable database just makes the application
    # exit and restart forever, which looks like an unexplained crash.
    & docker run --rm --add-host 'host.docker.internal:host-gateway' $PostgresImage `
        pg_isready -h $h -p $p -t 10 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError @"
Cannot reach a PostgreSQL server at ${h}:${p}.

Check, on the database server:
  - PostgreSQL is running and listening on that port
  - postgresql.conf has        listen_addresses = '*'   (or the address this machine uses)
  - pg_hba.conf accepts connections from Docker, e.g.
        host  $d  $u  172.16.0.0/12  scram-sha-256
  - Windows Firewall / any firewall between the two machines allows port $p

docs\HOST-DB-SETUP.md has the full instructions. Nothing has been changed.
"@
    }
    Write-Ok 'Server is reachable'

    # Credentials, and a look at what is already in there.
    $tables = & docker run --rm --add-host 'host.docker.internal:host-gateway' `
        -e "PGPASSWORD=$pw" $PostgresImage `
        psql -h $h -p $p -U $u -d $d -tAc "select count(*) from information_schema.tables where table_schema = 'public'"
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError @"
Connected to the server, but could not open database '$d' as role '$u'.

Check the database exists, the role exists, the password in .env is correct, and the
role may connect to that database. docs\HOST-DB-SETUP.md has the commands.

Nothing has been changed.
"@
    }
    $count = ($tables -join '').Trim()

    if ($count -eq '0') {
        Write-Ok "Database '$d' is empty and ready - tables will be created on first start"
        return
    }

    $isOurs = & docker run --rm --add-host 'host.docker.internal:host-gateway' `
        -e "PGPASSWORD=$pw" $PostgresImage `
        psql -h $h -p $p -U $u -d $d -tAc "select to_regclass('public.flyway_schema_history') is not null"
    if ($LASTEXITCODE -eq 0 -and (($isOurs -join '').Trim() -eq 't')) {
        Write-Ok "Database '$d' already holds an installation of this system - it will be upgraded in place, not replaced"
        return
    }

    # Refusing here is the point: the schema tool will not adopt a database it does not
    # recognise, and a half-applied install into someone else's data is far worse than
    # stopping now.
    Stop-WithError @"
Database '$d' already contains $count table(s), and they were not created by this system.

This application requires a database of its own. Installing into a database that holds
other data is not supported and will fail partway through.

Create an empty database for it (see docs\HOST-DB-SETUP.md), put its name in .env as
ERP_DB_NAME, and run .\install.ps1 again. Nothing has been changed.
"@
}

# ---------------------------------------------------------------------------
# 5. Start
# ---------------------------------------------------------------------------
function Start-System {
    Write-Step 'Starting the system'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ScriptDir 'orbixerp.ps1') start
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Show-Summary {
    $pass = Get-EnvValue 'ERP_BOOTSTRAP_ADMIN_PASSWORD'
    $port = Get-EnvValue 'ERP_HTTP_PORT' '8080'
    $machine = $env:COMPUTERNAME

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host '  OrbixERP is installed and running' -ForegroundColor White
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host ''
    Write-Host '  Open in a browser'
    Write-Host "      on this machine   http://localhost:$port"
    Write-Host "      from the network  http://${machine}:$port"
    Write-Host ''
    Write-Host '  Sign in with'
    Write-Host '      username          rootadmin'
    Write-Host "      password          $pass" -ForegroundColor White
    Write-Host ''
    Write-Host '  Change that password after your first sign-in.' -ForegroundColor Yellow
    Write-Host '  It is also saved in .env, which you should keep private.'
    Write-Host ''
    Write-Host '  From now on, double-click' -NoNewline
    Write-Host '  OrbixERP.cmd' -ForegroundColor White -NoNewline
    Write-Host '  to start, stop, check or back up.'
    Write-Host ''
    Write-Host '  Or type, if you prefer:'
    Write-Host '      .\orbixerp.ps1 status       is it running?'
    Write-Host '      .\orbixerp.ps1 backup       back up the database'
    Write-Host '      .\orbixerp.ps1 stop         shut down (your data is kept)'
    Write-Host '      .\orbixerp.ps1 start        start again'
    Write-Host ''
    Write-Host '  Back up these three things together - a database backup alone'
    Write-Host '  cannot be signed into without them:'
    Write-Host '      backups\   .env   secrets\'
    Write-Host ''
    Write-Host '  Guides in docs\ : INSTALL, OPERATIONS, HOST-DB-SETUP, TROUBLESHOOTING'
    Write-Host '  Each one comes as .md and as plain .txt - double-click the .txt to read it.'
    Write-Host ''
}

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '  OrbixERP - installation' -ForegroundColor White
Write-Host "  version $(Get-Meta 'ERP_VERSION') ($(Get-Meta 'BUNDLE_ARCH'))"

Write-Step 'Checking this machine'
Test-Architecture
Test-Docker

Import-Images
New-EnvFile
Set-Secrets
Set-JwtKeys

Write-Step 'Final checks before starting'
Test-Port
Test-HostDatabase

Start-System

# Only now, once the system has genuinely come up: first-run setup has done its job and
# must not run again. Doing this earlier would leave a failed install unable to create
# its administrator on the next attempt.
Set-EnvValue 'ERP_BOOTSTRAP_ENABLED' 'false'

Show-Summary
