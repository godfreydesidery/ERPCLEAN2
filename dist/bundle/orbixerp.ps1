<#
    OrbixERP - day-to-day control script (Windows).
    Linux / macOS users: use orbixerp.sh instead.

      .\orbixerp.ps1 start            start the system
      .\orbixerp.ps1 stop             stop it (your data is kept)
      .\orbixerp.ps1 restart          apply changes made to .env
      .\orbixerp.ps1 status           is it running and healthy?
      .\orbixerp.ps1 logs             show application logs
      .\orbixerp.ps1 backup           write a database backup into backups\
      .\orbixerp.ps1 restore <file>   REPLACE the database from a backup file
      .\orbixerp.ps1 update <dir>     upgrade to a newer release bundle
      .\orbixerp.ps1 version          what is installed

    If Windows refuses to run this file, start it like this:
      powershell -ExecutionPolicy Bypass -File .\orbixerp.ps1 status

    This script works out which database mode you are in (ERP_DB_MODE in .env) and
    assembles the right docker compose command, so you never have to.
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Command = 'help',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$EnvFile        = Join-Path $ScriptDir '.env'
$PostgresImage  = 'postgres:15-alpine'
# $Project / $ApiContainer are resolved from .env further down, once Get-EnvValue exists -
# ERP_STACK_NAME namespaces the compose project, containers, network and volume.

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
function Write-Step { param([string]$m) Write-Host "==> $m" -ForegroundColor White }
function Write-Ok   { param([string]$m) Write-Host "  ok  $m" -ForegroundColor Green }
function Write-Warn { param([string]$m) Write-Host "warn  $m" -ForegroundColor Yellow }
function Stop-WithError {
    param([string]$m)
    Write-Host ''
    Write-Host 'ERROR' -ForegroundColor Red
    Write-Host ''
    Write-Host $m
    Write-Host ''
    exit 1
}

# ---------------------------------------------------------------------------
# .env access
#
# The file is read line by line rather than parsed as key=value pairs by a library,
# because values may legitimately contain '=' and '#'. Carriage returns are trimmed
# on read and never written on save: a stray \r inside a container environment
# variable produces a password that is silently wrong or a JDBC URL that will not parse.
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

function Set-EnvValue {
    param([string]$Key, [string]$Value)
    $lines = @()
    if (Test-Path $EnvFile) {
        $lines = [System.IO.File]::ReadAllText($EnvFile) -split "`r?`n"
    }
    $replaced = $false
    $out = foreach ($line in $lines) {
        if (-not $replaced -and $line -match "^\s*$([regex]::Escape($Key))=") { $replaced = $true; "$Key=$Value" }
        else { $line }
    }
    if (-not $replaced) { $out = @($out) + "$Key=$Value" }
    Write-LfFile -Path $EnvFile -Lines $out
}

# Writes LF line endings and no byte-order mark. Both matter: Docker passes each .env
# line into the container verbatim, so CRLF would append an invisible \r to every value.
function Write-LfFile {
    param([string]$Path, [string[]]$Lines)
    $text = ($Lines -join "`n")
    if (-not $text.EndsWith("`n")) { $text += "`n" }
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-EnvFile {
    if (-not (Test-Path $EnvFile)) {
        Stop-WithError "No .env file found in $ScriptDir.`nThis system has not been installed yet. Run:`n    .\install.ps1"
    }
}

# Resolved once, now that Get-EnvValue exists. Must match the ${ERP_STACK_NAME:-orbixerp} defaults
# in the compose files, or the script would look for containers and networks that do not exist.
$Project      = Get-EnvValue 'ERP_STACK_NAME' 'orbixerp'
$ApiContainer = "$Project-api"

# ---------------------------------------------------------------------------
# Compose command assembly - the heart of the two database modes
# ---------------------------------------------------------------------------
function Get-ComposeFiles {
    $mode = Get-EnvValue 'ERP_DB_MODE' 'docker'
    $files = @('-f', 'docker-compose.yml')
    switch ($mode) {
        'docker' { $files += @('-f', 'docker-compose.db-docker.yml') }
        'host'   { $files += @('-f', 'docker-compose.db-host.yml') }
        default  {
            Stop-WithError "ERP_DB_MODE in .env is '$mode' but must be either 'docker' or 'host'.`n  docker = we run the database for you in a container`n  host   = you point us at your own PostgreSQL server (see docs\HOST-DB-SETUP.md)"
        }
    }
    if ((Get-EnvValue 'ERP_TLS_ENABLED' 'false') -eq 'true') { $files += @('-f', 'docker-compose.tls.yml') }
    return $files
}

function Invoke-Compose {
    param([string[]]$ComposeArgs)
    $files = Get-ComposeFiles
    # `| Out-Host` matters: without it docker's console output would be returned down the
    # pipeline along with the exit code, and callers testing the result against 0 would be
    # comparing against an array of log lines instead.
    & docker compose -p $Project @files @ComposeArgs | Out-Host
    return $LASTEXITCODE
}

function Assert-Docker {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Stop-WithError "Docker is not installed, or not on this account's PATH.`nInstall Docker Desktop for Windows from docker.com and start it."
    }
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker info *> $null } catch { }
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "Docker is installed but not running.`nStart Docker Desktop, wait for it to report 'Engine running', then try again."
    }
    try { $ErrorActionPreference = 'SilentlyContinue'; & docker compose version *> $null } catch { }
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "This version of Docker is too old - it has no 'docker compose' command.`nUpgrade Docker Desktop."
    }
}

# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------
function Get-ContainerHealth {
    # Returns: healthy | starting | unhealthy | running | stopped | absent
    #
    # Both fields are needed: a container that has crashed can still report a stale health
    # value, so the running state is checked first. This is polled every 5 seconds while
    # waiting for startup, and the container legitimately does not exist yet on the first
    # poll or two - hence the deliberately quiet error handling.
    $out = $null
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        $out = & docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ApiContainer 2>$null
    } catch { return 'absent' }
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($out)) { return 'absent' }
    $parts  = ($out | Select-Object -First 1).Trim() -split '\|'
    if ($parts[0] -ne 'running') { return 'stopped' }
    if ($parts[1] -eq 'none')    { return 'running' }
    return $parts[1]
}

function Wait-Healthy {
    param([int]$TimeoutSeconds = 900)
    Write-Step 'Waiting for the system to become ready (first start applies database migrations and can take several minutes)'
    $waited = 0
    while ($waited -lt $TimeoutSeconds) {
        $state = Get-ContainerHealth
        if ($state -eq 'healthy') { Write-Host ''; Write-Ok 'System is ready.'; return }
        if ($state -eq 'stopped') {
            Write-Host ''
            Show-FailureContext
            Stop-WithError "The application stopped unexpectedly. The last log lines are above.`ndocs\TROUBLESHOOTING.md lists what usually causes this."
        }
        Write-Host '.' -NoNewline
        Start-Sleep -Seconds 5
        $waited += 5
    }
    Write-Host ''
    Show-FailureContext
    Stop-WithError "The system did not become ready within $([int]($TimeoutSeconds / 60)) minutes.`nThe last log lines are above - docs\TROUBLESHOOTING.md explains the common causes."
}

function Show-FailureContext {
    Write-Warn 'Last 40 log lines from the application:'
    & docker logs --tail 40 $ApiContainer | ForEach-Object { Write-Host "    $_" }
}

function Get-AccessUrl {
    if ((Get-EnvValue 'ERP_TLS_ENABLED' 'false') -eq 'true') {
        $host_ = Get-EnvValue 'ERP_PUBLIC_HOST' 'localhost'
        $port  = Get-EnvValue 'ERP_HTTPS_PORT' '443'
        if ($port -eq '443') { return "https://$host_" }
        return "https://${host_}:${port}"
    }
    $port = Get-EnvValue 'ERP_HTTP_PORT' '8080'
    return "http://localhost:$port"
}

# ---------------------------------------------------------------------------
# Database access for backup / restore
#
# One code path for both modes: a throwaway postgres container runs pg_dump or
# pg_restore. In docker mode it joins the stack's private network; in host mode it
# reaches your own server. This is why the postgres image ships even when you bring
# your own database.
# ---------------------------------------------------------------------------
function Invoke-PgTool {
    param([string[]]$ToolArgs)
    $mode = Get-EnvValue 'ERP_DB_MODE' 'docker'
    $backups = Join-Path $ScriptDir 'backups'
    $dockerArgs = @('run', '--rm',
        '-e', "PGPASSWORD=$(Get-EnvValue 'ERP_DB_PASSWORD')",
        '-v', "${backups}:/backups")

    if ($mode -eq 'docker') {
        try { $ErrorActionPreference = 'SilentlyContinue'; & docker network inspect "${Project}_default" *> $null } catch { }
        if ($LASTEXITCODE -ne 0) {
            Stop-WithError "The system is not running, so its database cannot be reached.`nStart it first:  .\orbixerp.ps1 start"
        }
        $dockerArgs += @('--network', "${Project}_default")
    } else {
        $dockerArgs += @('--add-host', 'host.docker.internal:host-gateway')
    }

    # Out-Host for the same reason as Invoke-Compose - only the exit code is returned.
    & docker @dockerArgs $PostgresImage @ToolArgs | Out-Host
    return $LASTEXITCODE
}

function Get-DbHost {
    if ((Get-EnvValue 'ERP_DB_MODE' 'docker') -eq 'docker') { return 'db' }
    return (Get-EnvValue 'ERP_DB_HOST' 'host.docker.internal')
}
function Get-DbPort {
    if ((Get-EnvValue 'ERP_DB_MODE' 'docker') -eq 'docker') { return '5432' }
    return (Get-EnvValue 'ERP_DB_PORT' '5432')
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------
function Invoke-Start {
    Assert-EnvFile; Assert-Docker
    Write-Step "Starting OrbixERP (database mode: $(Get-EnvValue 'ERP_DB_MODE' 'docker'))"
    if ((Invoke-Compose @('up', '-d')) -ne 0) { Stop-WithError 'Docker could not start the system. The error from Docker is above.' }
    Wait-Healthy
    Write-Host ''
    Write-Host "  Open  $(Get-AccessUrl)" -ForegroundColor White
    Write-Host ''
}

function Invoke-Stop {
    Assert-EnvFile; Assert-Docker
    Write-Step 'Stopping OrbixERP'
    # `down` WITHOUT -v. That flag would delete the database volume and everything in
    # it, and is never used anywhere in this script.
    [void](Invoke-Compose @('down'))
    Write-Ok "Stopped. Your data is intact - '.\orbixerp.ps1 start' brings it back."
}

function Invoke-Status {
    Assert-EnvFile; Assert-Docker
    Write-Step 'Containers'
    [void](Invoke-Compose @('ps'))
    Write-Host ''
    Write-Step 'Application health'
    switch (Get-ContainerHealth) {
        'healthy'   { Write-Ok "healthy - $(Get-AccessUrl)" }
        'starting'  { Write-Warn 'starting - still applying migrations or warming up' }
        'unhealthy' { Write-Warn 'unhealthy - see .\orbixerp.ps1 logs and docs\TROUBLESHOOTING.md' }
        'absent'    { Write-Warn 'not running - .\orbixerp.ps1 start' }
        default     { Write-Warn (Get-ContainerHealth) }
    }
    Write-Host ''
    Write-Step 'Version'
    Write-Host "  installed: $(Get-EnvValue 'ERP_VERSION' 'unknown')   database mode: $(Get-EnvValue 'ERP_DB_MODE' 'docker')"
}

function Invoke-Logs {
    Assert-EnvFile; Assert-Docker
    if ($null -eq $Rest -or $Rest.Count -eq 0) { [void](Invoke-Compose @('logs', '--tail', '200', 'api')) }
    else { [void](Invoke-Compose (@('logs') + $Rest)) }
}

# Returns the path of the backup file it wrote, so Invoke-Update can offer it as the
# rollback point. Progress goes to the host, not down the pipeline.
function Invoke-Backup {
    Assert-EnvFile; Assert-Docker
    $backups = Join-Path $ScriptDir 'backups'
    if (-not (Test-Path $backups)) { New-Item -ItemType Directory -Path $backups | Out-Null }

    $name = "orbixerp_$(Get-Date -Format 'yyyyMMdd_HHmmss').dump"
    $file = Join-Path $backups $name

    Write-Step "Backing up the database to backups\$name"
    # -Fc = PostgreSQL's compressed custom format, which pg_restore reads.
    # The dump is written INSIDE the container to /backups (a bind mount) rather than
    # redirected through PowerShell - PowerShell's redirection would corrupt binary output.
    $code = Invoke-PgTool @('pg_dump', '-h', (Get-DbHost), '-p', (Get-DbPort),
                            '-U', (Get-EnvValue 'ERP_DB_USER' 'erp'),
                            '-d', (Get-EnvValue 'ERP_DB_NAME' 'erp'),
                            '-Fc', '-f', "/backups/$name")
    if ($code -ne 0) {
        Stop-WithError 'Backup failed. Nothing was written. The database may be unreachable - check .\orbixerp.ps1 status.'
    }
    # An empty file is a failed backup that looks like a successful one. Refuse it so it
    # can never be mistaken for a safe rollback point during an update.
    if (-not (Test-Path $file) -or (Get-Item $file).Length -eq 0) {
        Stop-WithError 'Backup produced an empty file. Treating this as a failure - do not rely on it.'
    }
    $sizeMb = [math]::Round((Get-Item $file).Length / 1MB, 1)
    Write-Ok "Backup complete: backups\$name ($sizeMb MB)"

    $retain = 0
    if ([int]::TryParse((Get-EnvValue 'ERP_BACKUP_RETAIN_DAYS' '14'), [ref]$retain) -and $retain -gt 0) {
        Get-ChildItem $backups -Filter 'orbixerp_*.dump' -File |
            Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$retain) } |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "  Backups older than $retain days have been removed."
    }
    return $file
}

function Invoke-Restore {
    Assert-EnvFile; Assert-Docker
    if ($null -eq $Rest -or $Rest.Count -eq 0) {
        Stop-WithError "Which backup? Usage:`n    .\orbixerp.ps1 restore backups\erp_20260801_120000.dump"
    }
    $file = $Rest[0]
    if (-not (Test-Path $file)) { Stop-WithError "Backup file not found: $file" }

    $base    = Split-Path -Leaf $file
    $backups = Join-Path $ScriptDir 'backups'
    if (-not (Test-Path $backups)) { New-Item -ItemType Directory -Path $backups | Out-Null }
    $target = Join-Path $backups $base
    if (-not (Test-Path $target)) { Copy-Item $file $target }

    Write-Host ''
    Write-Host "This REPLACES the current database with the contents of $base." -ForegroundColor Yellow
    Write-Host 'Everything recorded since that backup was taken will be lost.'
    Write-Host ''
    $answer = Read-Host 'Type RESTORE to continue'
    if ($answer -ne 'RESTORE') { Stop-WithError 'Cancelled. Nothing was changed.' }

    Write-Step 'Stopping the application (the database keeps running)'
    [void](Invoke-Compose @('stop', 'api'))

    $dbUser = Get-EnvValue 'ERP_DB_USER' 'erp'
    $dbName = Get-EnvValue 'ERP_DB_NAME' 'erp'
    $safety = "safety-before-restore-$(Get-Date -Format 'yyyyMMdd-HHmmss').dump"

    # A restore is the one irreversible command in this script, and until now it took no
    # safety copy: restoring the wrong file destroyed the current database with nothing to
    # go back to. Take one first, and refuse to continue if it fails.
    Write-Step 'Taking a safety copy of the CURRENT database first'
    $code = Invoke-PgTool @('pg_dump', '-h', (Get-DbHost), '-p', (Get-DbPort),
                            '-U', $dbUser, '-d', $dbName, '-Fc', '-f', "/backups/$safety")
    if ($code -ne 0) {
        Stop-WithError "Could not back up the current database, so the restore has been cancelled.`nNothing was changed. Check that the database is running:  .\orbixerp.ps1 status"
    }
    Write-Ok "Safety copy saved: backups/$safety"

    # Empty the schema, then restore into it. This replaces `pg_restore --clean --if-exists`,
    # which drops objects one by one in the dump's own order and fails whenever the live
    # database holds an object the backup does not know about — a constraint from a newer
    # release, say — because the dependent object blocks the drop. The restore then
    # half-succeeded, and the old code downgraded that to a warning and printed
    # "Restore complete" over a database that had only partly been rolled back.
    #
    # DROP SCHEMA ... CASCADE clears everything regardless of the backup's contents and
    # needs only the owning role — not a superuser, and not CREATEDB.
    Write-Step 'Clearing the current database'
    $code = Invoke-PgTool @('psql', '-h', (Get-DbHost), '-p', (Get-DbPort),
                            '-U', $dbUser, '-d', $dbName, '-v', 'ON_ERROR_STOP=1', '-q',
                            '-c', 'SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid();',
                            '-c', 'DROP SCHEMA IF EXISTS public CASCADE;',
                            '-c', "CREATE SCHEMA public AUTHORIZATION ""$dbUser"";",
                            '-c', "GRANT ALL ON SCHEMA public TO ""$dbUser"";")
    if ($code -ne 0) {
        Stop-WithError "Could not clear the database, so nothing has been restored.`nYour data is untouched and a safety copy is at backups/$safety`nStart the system again with:  .\orbixerp.ps1 start"
    }

    Write-Step 'Restoring'
    # No --clean: the schema is already empty. Errors are now FATAL — a partly-restored
    # database that reports success is worse than a failure you can see.
    $code = Invoke-PgTool @('pg_restore', '-h', (Get-DbHost), '-p', (Get-DbPort),
                            '-U', $dbUser, '-d', $dbName,
                            '--no-owner', '--exit-on-error', "/backups/$base")
    if ($code -ne 0) {
        Stop-WithError "The restore FAILED and the database is now incomplete. Do not start the system.`nRestore the safety copy taken a moment ago:`n    .\orbixerp.ps1 restore backups/$safety`nIf that also fails, contact support and quote both file names."
    }

    Write-Step 'Restarting the application'
    [void](Invoke-Compose @('up', '-d'))
    Wait-Healthy
    Write-Ok 'Restore complete.'
    Write-Host "  The safety copy of the database as it was before this restore is kept at"
    Write-Host "  backups/$safety in case you need to undo this."
}

function Invoke-Update {
    Assert-EnvFile; Assert-Docker
    if ($null -eq $Rest -or $Rest.Count -eq 0) {
        Stop-WithError "Which release? Usage:`n    .\orbixerp.ps1 update C:\path\to\erp-1.1.0"
    }
    $src = $Rest[0]
    if (-not (Test-Path $src -PathType Container)) { Stop-WithError "Not a folder: $src" }
    $srcVersionFile = Join-Path $src 'VERSION'
    if (-not (Test-Path $srcVersionFile)) { Stop-WithError "$src does not look like an OrbixERP release bundle (no VERSION file)." }

    $meta = @{}
    foreach ($line in (Get-Content $srcVersionFile)) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $meta[$matches[1]] = $matches[2].Trim("`r") }
    }
    $newVersion = $meta['ERP_VERSION']
    $newArch    = $meta['BUNDLE_ARCH']
    $curVersion = Get-EnvValue 'ERP_VERSION' 'unknown'

    Write-Step "Updating from $curVersion to $newVersion"

    $hostArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
    if ($newArch -and $newArch -ne $hostArch) {
        Stop-WithError "That release bundle is built for $newArch, but this machine is $hostArch.`nAsk your supplier for the $hostArch bundle."
    }

    # A backup BEFORE anything else, and a hard stop if it fails.
    #
    # This is not belt-and-braces. Database migrations only run forwards: once the new
    # version has upgraded the schema, the old version will not start against it.
    # Restoring this backup is the ONLY way to undo an update.
    Write-Step 'Taking a safety backup first - the update stops here if it fails'
    $backupFile = Invoke-Backup
    Write-Host "  If this update goes wrong, undo it with:"
    Write-Host "    .\orbixerp.ps1 restore $backupFile"

    Write-Step 'Loading the new application image'
    $loaded = 0
    Get-ChildItem (Join-Path $src 'images') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '*.tar' -or $_.Name -like '*.tar.gz' } |
        ForEach-Object {
            Write-Host "  loading $($_.Name)"
            & docker load -i $_.FullName | Out-Null
            if ($LASTEXITCODE -ne 0) { Stop-WithError "Could not load $($_.Name)" }
            $loaded++
        }
    if ($loaded -eq 0) { Stop-WithError "No image files found in $src\images\" }

    Write-Step 'Updating configuration files'
    # .env, secrets\ and backups\ are yours and are never touched.
    foreach ($f in @('docker-compose.yml', 'docker-compose.db-docker.yml', 'docker-compose.db-host.yml',
                     'docker-compose.tls.yml', 'Caddyfile', '.env.example', 'VERSION', 'RELEASE-NOTES.md')) {
        $from = Join-Path $src $f
        if (Test-Path $from) { Copy-Item $from (Join-Path $ScriptDir $f) -Force }
    }
    $srcDocs = Join-Path $src 'docs'
    if (Test-Path $srcDocs) {
        $dstDocs = Join-Path $ScriptDir 'docs'
        if (Test-Path $dstDocs) { Remove-Item $dstDocs -Recurse -Force }
        Copy-Item $srcDocs $dstDocs -Recurse
    }

    Set-EnvValue 'ERP_VERSION' $newVersion
    # First-run setup must not re-trigger on an existing database.
    Set-EnvValue 'ERP_BOOTSTRAP_ENABLED' 'false'

    Write-Step "Starting version $newVersion"
    [void](Invoke-Compose @('up', '-d'))
    Wait-Healthy

    # Control scripts replaced last. Safe mid-run: PowerShell parses the whole file
    # before executing any of it.
    # The Windows double-click launchers are refreshed too, so an updated installation does
    # not keep last year's Setup.cmd sitting next to this year's application.
    foreach ($f in @('orbixerp.ps1', 'install.ps1', 'orbixerp.sh', 'install.sh',
                     'Setup.cmd', 'setup-wizard.ps1', 'Install.cmd', 'OrbixERP.cmd',
                     'Remote-Setup.cmd', 'remote-setup-wizard.ps1')) {
        $from = Join-Path $src $f
        if (Test-Path $from) { Copy-Item $from (Join-Path $ScriptDir $f) -Force }
    }

    Write-Host ''
    Write-Ok "Updated to $newVersion."
    Write-Host "  Rollback if needed:  .\orbixerp.ps1 restore $backupFile"
    Write-Host ''
}

function Invoke-Version {
    Write-Host "installed version : $(Get-EnvValue 'ERP_VERSION' 'unknown')"
    Write-Host "database mode     : $(Get-EnvValue 'ERP_DB_MODE' 'docker')"
    $vf = Join-Path $ScriptDir 'VERSION'
    if (Test-Path $vf) { Write-Host ''; Write-Host 'release bundle:'; Get-Content $vf | ForEach-Object { Write-Host "  $_" } }
}

function Invoke-Help {
    Write-Host @'
OrbixERP - control script

  .\orbixerp.ps1 start            start the system
  .\orbixerp.ps1 stop             stop it (your data is kept)
  .\orbixerp.ps1 restart          apply changes made to .env
  .\orbixerp.ps1 status           is it running and healthy?
  .\orbixerp.ps1 logs             show application logs
  .\orbixerp.ps1 backup           write a database backup into backups\
  .\orbixerp.ps1 restore <file>   REPLACE the database from a backup file
  .\orbixerp.ps1 update <dir>     upgrade to a newer release bundle
  .\orbixerp.ps1 version          what is installed

Guides are in the docs\ folder.
'@
}

# ---------------------------------------------------------------------------
switch ($Command.ToLower()) {
    'start'   { Invoke-Start }
    'up'      { Invoke-Start }
    'stop'    { Invoke-Stop }
    'down'    { Invoke-Stop }
    'restart' { Invoke-Stop; Invoke-Start }
    'status'  { Invoke-Status }
    'ps'      { Invoke-Status }
    'logs'    { Invoke-Logs }
    'backup'  { [void](Invoke-Backup) }
    'restore' { Invoke-Restore }
    'update'  { Invoke-Update }
    'version' { Invoke-Version }
    'config'  { Assert-EnvFile; Assert-Docker; [void](Invoke-Compose @('config')) }
    'help'    { Invoke-Help }
    default   { Stop-WithError "Unknown command '$Command'. Run '.\orbixerp.ps1 help' to see what is available." }
}
