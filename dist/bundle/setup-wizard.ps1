<#
    OrbixERP - graphical setup wizard for Windows.

    Started by double-clicking Setup.cmd. Not meant to be run directly, because Windows
    blocks PowerShell scripts that arrived from another computer; Setup.cmd lifts that
    block for this one script.

    It does the whole installation: checks the machine, asks for a folder and settings,
    copies the files, writes .env, generates passwords and signing keys, loads the Docker
    images, starts the system, waits until it is genuinely ready, and reports the address
    and the administrator password.

    Built on WinForms rather than anything else because .NET ships inside Windows: no
    runtime to install first, nothing downloaded, and it works on a machine with no
    internet connection at all. That matters - the entire promise of this bundle is that
    Docker is the only prerequisite.

    ASCII only, deliberately. Windows PowerShell 5.1 reads a file without a byte-order
    mark as CP1252, and a UTF-8 dash would decode into a curly quote, which PowerShell
    treats as a string delimiter - breaking the script on the client's machine while
    looking perfect here.
#>

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------------------------------------------------------------------
# State gathered across the pages
# ---------------------------------------------------------------------------
$S = @{
    InstallDir   = 'C:\OrbixERP'
    DbMode       = 'docker'
    DbHost       = 'host.docker.internal'
    DbPort       = '5432'
    DbName       = 'erp'
    DbUser       = 'erp'
    DbPassword   = ''
    HttpPort     = '8080'
    BindAddr     = '0.0.0.0'
    StackName    = 'orbixerp'
    OrgName      = 'My Organisation'
    CompanyName  = 'My Company'
    BranchName   = 'Head Office'
    TimeZone     = 'Africa/Dar_es_Salaam'
    Currency     = 'TZS'
    AdminPass    = ''
    Version      = ''
    BundleArch   = ''
    Shortcut     = $true
    # 'install' for a fresh machine, 'update' when the chosen folder already holds an
    # OrbixERP. Under continuous release the second is by far the more common.
    Mode             = 'install'
    InstalledVersion = ''
    # Set to 'reuse' when a database volume from a previous installation is found. That is
    # the ONLY value it ever takes - the wizard has no option that destroys a database.
    ExistingDb       = ''
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Get-Meta {
    param([string]$Key)
    $f = Join-Path $SourceDir 'VERSION'
    if (-not (Test-Path $f)) { return '' }
    foreach ($line in (Get-Content $f)) {
        if ($line -match "^$([regex]::Escape($Key))=(.*)$") { return $matches[1].Trim("`r") }
    }
    return ''
}

# Passwords are generated with the system RNG rather than by shelling out, so this works
# before any Docker image has been loaded and never flashes a console window.
function New-Password {
    param([int]$Length = 20)
    $alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object 'byte[]' $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

# Windows system locations are off limits by design, not by accident.
#
# The system installs to a dedicated top-level folder - C:\OrbixERP - the way Bitnami and similar
# stacks do. Three reasons, all practical:
#
#   - No administrator rights are needed to install, run, update or back it up.
#   - The application, its settings, its keys and its backups sit in ONE tree the operator
#     can copy, move or hand to their IT provider.
#   - Program Files is subject to file virtualisation and Controlled Folder Access, which
#     silently redirect or block writes and produce faults that are miserable to diagnose
#     months later.
function Test-SystemLocation {
    param([string]$Path)
    $p = $Path.TrimEnd('\')
    if ($p -match '^[A-Za-z]:$') { return $true }          # the bare root of a drive
    foreach ($name in @('ProgramFiles', 'ProgramFilesX86', 'Windows', 'System')) {
        $b = ''
        try { $b = [Environment]::GetFolderPath($name) } catch { }
        if (-not $b) { continue }
        $b = $b.TrimEnd('\')
        if ($p -eq $b -or $p.StartsWith($b + '\', [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

# Folders that get emptied, synced or cleaned behind the operator's back. Not forbidden -
# some people genuinely want a throwaway trial - but worth one question, because the install
# folder holds the database settings, the signing keys and the backups.
function Test-VolatileLocation {
    param([string]$Path)
    $p = $Path.TrimEnd('\')
    foreach ($v in @($env:TEMP, $env:TMP, (Join-Path $env:USERPROFILE 'Downloads'), (Join-Path $env:USERPROFILE 'Desktop'))) {
        if (-not $v) { continue }
        $v = $v.TrimEnd('\')
        if ($p -eq $v -or $p.StartsWith($v + '\', [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

# Actually tries to write, rather than reasoning about ACLs - the only reliable answer on
# Windows, where an inherited deny or a controlled-folder rule can defeat any calculation.
function Test-FolderWritable {
    param([string]$Path)
    $created = $null
    try {
        if (-not (Test-Path $Path)) {
            $created = (New-Item -ItemType Directory -Path $Path -Force -ErrorAction Stop)
        }
        $probe = Join-Path $Path ('.write-test-' + [System.IO.Path]::GetRandomFileName())
        [System.IO.File]::WriteAllText($probe, 'x')
        Remove-Item $probe -Force -ErrorAction SilentlyContinue
        return $true
    } catch {
        return $false
    } finally {
        # Leave nothing behind if the folder was created purely to test it and is still empty.
        if ($created -and (Test-Path $Path) -and -not (Get-ChildItem $Path -Force)) {
            Remove-Item $Path -Force -ErrorAction SilentlyContinue
        }
    }
}

# Does a database volume from a previous installation of this stack already exist?
#
# This matters more than it looks. The official Postgres image applies POSTGRES_PASSWORD only
# when it initialises an EMPTY data directory. Reinstalling over a surviving volume therefore
# writes a freshly generated password into .env that the database has never heard of, and
# every connection fails with "password authentication failed" - while the client's data sits
# there intact and unreachable, because nobody has the old password any more.
#
# Verified by experiment, not assumed.
function Test-DbVolumeExists {
    param([string]$StackName)
    $null = & docker volume inspect "$StackName-db-data" 2>&1
    return ($LASTEXITCODE -eq 0)
}

# Recovering that situation is possible because the Postgres image trusts connections over
# its own local socket regardless of password. So the password can be reset from inside the
# container to whatever the new .env says, and the existing data carries on being used.
function Reset-DbPassword {
    param([string]$StackName, [string]$InstallDir, [string]$DbUser, [string]$DbName, [string]$NewPassword)

    Write-Log 'Reconnecting to the existing database...'
    $files = @('-f', 'docker-compose.yml', '-f', 'docker-compose.db-docker.yml')

    if ((Invoke-Tracked 'docker' "compose -p $StackName $($files -join ' ') up -d db" $InstallDir) -ne 0) {
        throw 'Could not start the existing database.'
    }

    # Wait for it to accept connections before altering anything.
    $ready = $false
    foreach ($i in 1..60) {
        $null = & docker exec "$StackName-db" pg_isready -U $DbUser -d $DbName 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
        [System.Windows.Forms.Application]::DoEvents()
    }
    if (-not $ready) { throw 'The existing database did not start. It may be from an incompatible version.' }

    # Single-quotes inside the SQL are doubled; the generated password is alphanumeric, so
    # this is belt-and-braces rather than strictly necessary.
    $safe = $NewPassword.Replace("'", "''")
    $null = & docker exec "$StackName-db" psql -U $DbUser -d $DbName -c "ALTER USER $DbUser WITH PASSWORD '$safe'" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not reset the password on the existing database. It may have been created by a different product using the same name."
    }
    Write-Log 'Existing database reconnected - your data has been kept.'
}

function Test-PortFree {
    param([int]$Port)
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $a = $c.BeginConnect('127.0.0.1', $Port, $null, $null)
        if ($a.AsyncWaitHandle.WaitOne(700, $false) -and $c.Connected) { return $false }
        return $true
    } catch { return $true } finally { $c.Close() }
}

# Runs a command and streams its output into the log box while keeping the window
# responsive. A plain call would freeze the wizard for the minutes that loading images and
# migrating the database take, and a frozen window reads as a crashed one.
function Invoke-Tracked {
    param([string]$File, [string]$Arguments, [string]$WorkDir = $SourceDir)

    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    $p = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $WorkDir `
         -NoNewWindow -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile

    # DO NOT REMOVE. Reading .Handle forces .NET to keep the process handle open, and without
    # an open handle $p.ExitCode reads back as $null once the process ends - NOT 0. Every
    # success was therefore being compared as "null -ne 0" and reported as a failure: a
    # perfectly good `docker load` printed "Loaded image: caddy:2-alpine" and the wizard
    # declared the file damaged. -Wait avoids the problem, but blocking is exactly what this
    # function exists to avoid.
    $null = $p.Handle

    $shown = 0
    while (-not $p.HasExited) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 150
        $shown = Show-NewLines $outFile $shown
    }
    $p.WaitForExit()
    [void](Show-NewLines $outFile $shown)

    $err = (Get-Content $errFile -Raw -ErrorAction SilentlyContinue)
    if ($err -and $err.Trim()) { Write-Log $err.Trim() }

    Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue
    return $p.ExitCode
}

# Like Invoke-Tracked but returns what the command printed. Used for the short database
# queries, where the answer matters and the wait is a second or two.
function Invoke-Capture {
    param([string]$File, [string]$Arguments)
    $o = [System.IO.Path]::GetTempFileName(); $e = [System.IO.Path]::GetTempFileName()
    $p = Start-Process -FilePath $File -ArgumentList $Arguments -NoNewWindow -PassThru -Wait `
         -RedirectStandardOutput $o -RedirectStandardError $e
    $text = (Get-Content $o -Raw -ErrorAction SilentlyContinue)
    Remove-Item $o, $e -Force -ErrorAction SilentlyContinue
    return @{ Code = $p.ExitCode; Out = ("$text" -replace '\s', '') }
}

# Docker can be stopped, or crash, in the minutes between the welcome page and the install.
# Checked again here so the failure says "Docker has stopped" instead of surfacing as a raw
# daemon error from whichever command happened to run first.
function Assert-DockerAlive {
    param([string]$When = 'now')
    $null = & docker info 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("Docker Desktop is no longer running ($When).`r`n`r`n" +
               "It was running when this wizard started, so it has been closed or has crashed " +
               "since. Start Docker Desktop, wait for it to report 'Engine running', then run " +
               "Setup again.")
    }
}

function Show-NewLines {
    param([string]$Path, [int]$AlreadyShown)
    $lines = @(Get-Content $Path -ErrorAction SilentlyContinue)
    if ($lines.Count -gt $AlreadyShown) {
        for ($i = $AlreadyShown; $i -lt $lines.Count; $i++) { Write-Log $lines[$i] }
        return $lines.Count
    }
    return $AlreadyShown
}

function Write-Log {
    param([string]$Text)
    if ($null -eq $script:LogBox) { return }
    $script:LogBox.AppendText($Text.TrimEnd() + "`r`n")
    $script:LogBox.SelectionStart = $script:LogBox.TextLength
    $script:LogBox.ScrollToCaret()
    [System.Windows.Forms.Application]::DoEvents()
}

# ---------------------------------------------------------------------------
# Form scaffolding
# ---------------------------------------------------------------------------
$form                 = New-Object System.Windows.Forms.Form
$form.Text            = 'OrbixERP - Setup'
$form.Size            = New-Object System.Drawing.Size(700, 540)
$form.StartPosition   = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox     = $false
$form.BackColor       = [System.Drawing.Color]::White

$header = New-Object System.Windows.Forms.Panel
$header.Dock = 'Top'; $header.Height = 62; $header.BackColor = [System.Drawing.Color]::FromArgb(31, 60, 92)
$form.Controls.Add($header)

$hTitle = New-Object System.Windows.Forms.Label
$hTitle.ForeColor = [System.Drawing.Color]::White
$hTitle.Font = New-Object System.Drawing.Font('Segoe UI', 13, [System.Drawing.FontStyle]::Bold)
$hTitle.Location = New-Object System.Drawing.Point(18, 10)
$hTitle.Size = New-Object System.Drawing.Size(650, 26)
$header.Controls.Add($hTitle)

$hSub = New-Object System.Windows.Forms.Label
$hSub.ForeColor = [System.Drawing.Color]::FromArgb(200, 214, 232)
$hSub.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$hSub.Location = New-Object System.Drawing.Point(20, 36)
$hSub.Size = New-Object System.Drawing.Size(650, 20)
$header.Controls.Add($hSub)

$footer = New-Object System.Windows.Forms.Panel
$footer.Dock = 'Bottom'; $footer.Height = 56; $footer.BackColor = [System.Drawing.Color]::FromArgb(244, 246, 249)
$form.Controls.Add($footer)

function New-Button {
    param([string]$Text, [int]$X)
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $Text
    $b.Size = New-Object System.Drawing.Size(104, 30)
    $b.Location = New-Object System.Drawing.Point($X, 13)
    $b.FlatStyle = 'System'
    $footer.Controls.Add($b)
    return $b
}
$btnBack   = New-Button 'Back'   350
$btnNext   = New-Button 'Next'   462
$btnCancel = New-Button 'Cancel' 574

$body = New-Object System.Windows.Forms.Panel
$body.Dock = 'Fill'; $body.BackColor = [System.Drawing.Color]::White
$form.Controls.Add($body)
$body.BringToFront()

function New-Page {
    $p = New-Object System.Windows.Forms.Panel
    $p.Dock = 'Fill'; $p.Visible = $false; $p.BackColor = [System.Drawing.Color]::White
    $body.Controls.Add($p)
    return $p
}

function New-Label {
    param($Parent, [string]$Text, [int]$X, [int]$Y, [int]$W = 620, [int]$H = 20, [switch]$Bold, [switch]$Dim)
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $Text
    $l.Location = New-Object System.Drawing.Point($X, $Y)
    $l.Size = New-Object System.Drawing.Size($W, $H)
    $style = if ($Bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $l.Font = New-Object System.Drawing.Font('Segoe UI', 9, $style)
    if ($Dim) { $l.ForeColor = [System.Drawing.Color]::FromArgb(100, 106, 115) }
    $Parent.Controls.Add($l)
    return $l
}

function New-TextBox {
    param($Parent, [string]$Value, [int]$X, [int]$Y, [int]$W = 300)
    $t = New-Object System.Windows.Forms.TextBox
    $t.Text = $Value
    $t.Location = New-Object System.Drawing.Point($X, $Y)
    $t.Size = New-Object System.Drawing.Size($W, 22)
    $t.Font = New-Object System.Drawing.Font('Segoe UI', 9)
    $Parent.Controls.Add($t)
    return $t
}

# ---------------------------------------------------------------------------
# Page 0 - welcome and machine checks
# ---------------------------------------------------------------------------
$pgWelcome = New-Page
New-Label $pgWelcome 'This wizard installs OrbixERP on this computer.' 24 22 620 20 -Bold | Out-Null
New-Label $pgWelcome 'It creates the folder, writes the settings, generates the passwords and security keys, and starts everything. Nothing is downloaded - everything needed is in this bundle.' 24 46 620 40 -Dim | Out-Null
New-Label $pgWelcome 'Checks on this machine' 24 100 620 20 -Bold | Out-Null

$lstChecks = New-Object System.Windows.Forms.ListView
$lstChecks.Location = New-Object System.Drawing.Point(24, 124)
$lstChecks.Size = New-Object System.Drawing.Size(620, 190)
$lstChecks.View = 'Details'; $lstChecks.FullRowSelect = $true; $lstChecks.HeaderStyle = 'Nonclickable'
$lstChecks.Columns.Add('', 34) | Out-Null
$lstChecks.Columns.Add('Check', 210) | Out-Null
$lstChecks.Columns.Add('Result', 350) | Out-Null
$pgWelcome.Controls.Add($lstChecks)

$lblCheckHint = New-Label $pgWelcome '' 24 322 620 40 -Dim
$btnRecheck = New-Object System.Windows.Forms.Button
$btnRecheck.Text = 'Check again'; $btnRecheck.Size = New-Object System.Drawing.Size(110, 28)
$btnRecheck.Location = New-Object System.Drawing.Point(24, 366); $btnRecheck.FlatStyle = 'System'
$pgWelcome.Controls.Add($btnRecheck)

$script:ChecksPassed = $false

function Invoke-Checks {
    $lstChecks.Items.Clear()
    $ok = $true

    function Add-Check {
        param([bool]$Pass, [string]$Name, [string]$Detail)
        $i = New-Object System.Windows.Forms.ListViewItem(@($(if ($Pass) { 'OK' } else { 'X' }), $Name, $Detail))
        $i.ForeColor = if ($Pass) { [System.Drawing.Color]::FromArgb(20, 110, 50) } else { [System.Drawing.Color]::FromArgb(170, 30, 30) }
        $lstChecks.Items.Add($i) | Out-Null
        return $Pass
    }

    $S.Version = Get-Meta 'ERP_VERSION'
    $S.BundleArch = Get-Meta 'BUNDLE_ARCH'
    $hostArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }

    if ($S.BundleArch -and $S.BundleArch -ne $hostArch) {
        $ok = (Add-Check $false 'Processor type' "This bundle is for $($S.BundleArch); this PC is $hostArch") -and $ok
    } else {
        [void](Add-Check $true 'Processor type' $hostArch)
    }

    $imgDir = Join-Path $SourceDir 'images'
    $imgs = @()
    if (Test-Path $imgDir) { $imgs = @(Get-ChildItem $imgDir -File | Where-Object { $_.Name -like '*.tar*' }) }
    if ($imgs.Count -eq 0) {
        $ok = (Add-Check $false 'Installation files' 'The images folder is missing or empty - unpack the bundle again') -and $ok
    } else {
        [void](Add-Check $true 'Installation files' "$($imgs.Count) application images found")
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        $ok = (Add-Check $false 'Docker Desktop' 'Not installed - install it from docker.com, then click Check again') -and $ok
    } else {
        $null = & docker info 2>&1
        if ($LASTEXITCODE -ne 0) {
            $ok = (Add-Check $false 'Docker Desktop' 'Installed but not running - start it, wait for "Engine running"') -and $ok
        } else {
            $v = (& docker version --format '{{.Server.Version}}' | Select-Object -First 1)
            [void](Add-Check $true 'Docker Desktop' "running (version $v)")
        }
    }

    $script:ChecksPassed = $ok
    $lblCheckHint.Text = if ($ok) { 'Everything needed is present. Click Next to continue.' }
                         else { 'Fix the items marked X above, then click Check again. Nothing has been changed on this computer.' }
    $btnNext.Enabled = $ok
}
$btnRecheck.Add_Click({ Invoke-Checks })

# ---------------------------------------------------------------------------
# Page 1 - install folder
# ---------------------------------------------------------------------------
$pgFolder = New-Page
New-Label $pgFolder 'Where should OrbixERP be installed?' 24 22 620 20 -Bold | Out-Null
New-Label $pgFolder 'OrbixERP installs into its own folder, not into Windows Program Files. Everything it needs - settings, security keys and backups - stays together in one place you can copy, move or hand to your IT provider, and no administrator rights are needed to run or update it.' 24 46 620 52 -Dim | Out-Null
$txtFolder = New-TextBox $pgFolder $S.InstallDir 24 112 500
$btnBrowse = New-Object System.Windows.Forms.Button
$btnBrowse.Text = 'Browse...'; $btnBrowse.Size = New-Object System.Drawing.Size(110, 24)
$btnBrowse.Location = New-Object System.Drawing.Point(534, 111); $btnBrowse.FlatStyle = 'System'
$pgFolder.Controls.Add($btnBrowse)
$btnBrowse.Add_Click({
    $d = New-Object System.Windows.Forms.FolderBrowserDialog
    $d.Description = 'Choose where to install OrbixERP'
    if ($d.ShowDialog() -eq 'OK') { $txtFolder.Text = Join-Path $d.SelectedPath 'OrbixERP' }
})
$lblFolderNote = New-Label $pgFolder '' 24 144 620 44 -Dim

# Live feedback, so nothing refused on Next is ever a surprise.
$txtFolder.Add_TextChanged({
    $p = $txtFolder.Text.Trim()
    $red  = [System.Drawing.Color]::FromArgb(170, 30, 30)
    $grey = [System.Drawing.Color]::FromArgb(100, 106, 115)
    if (-not $p) { $lblFolderNote.Text = ''; return }

    if (Test-SystemLocation $p) {
        $lblFolderNote.Text = 'Not allowed - this is a Windows system folder. OrbixERP installs into its own folder, such as C:\OrbixERP.'
        $lblFolderNote.ForeColor = $red
    } elseif (Test-Path (Join-Path $p '.env')) {
        $lblFolderNote.Text = 'An OrbixERP is already installed here. Choose a different folder - installing over it would stop the existing one working.'
        $lblFolderNote.ForeColor = $red
    } elseif (Test-VolatileLocation $p) {
        $lblFolderNote.Text = 'Not recommended - Windows empties or synchronises this folder, and your settings and backups live here.'
        $lblFolderNote.ForeColor = $red
    } elseif (Test-Path $p) {
        $lblFolderNote.Text = 'This folder already exists and will be used.'
        $lblFolderNote.ForeColor = $grey
    } else {
        $lblFolderNote.Text = 'This folder will be created.'
        $lblFolderNote.ForeColor = $grey
    }
})

# ---------------------------------------------------------------------------
# Page 2 - database
# ---------------------------------------------------------------------------
$pgDb = New-Page
New-Label $pgDb 'Where should the data be stored?' 24 22 620 20 -Bold | Out-Null

$rbDocker = New-Object System.Windows.Forms.RadioButton
$rbDocker.Text = 'Install a database for me  (recommended)'
$rbDocker.Location = New-Object System.Drawing.Point(24, 54); $rbDocker.Size = New-Object System.Drawing.Size(600, 22)
$rbDocker.Checked = $true; $rbDocker.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgDb.Controls.Add($rbDocker)
New-Label $pgDb 'Nothing extra to install. Your data is stored on this computer and survives restarts and upgrades.' 44 76 580 20 -Dim | Out-Null

$rbHost = New-Object System.Windows.Forms.RadioButton
$rbHost.Text = 'Use a PostgreSQL server we already run'
$rbHost.Location = New-Object System.Drawing.Point(24, 106); $rbHost.Size = New-Object System.Drawing.Size(600, 22)
$rbHost.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgDb.Controls.Add($rbHost)
New-Label $pgDb 'Requires PostgreSQL 15 with an empty database already created. See docs\HOST-DB-SETUP.txt.' 44 128 580 20 -Dim | Out-Null

$grpHost = New-Object System.Windows.Forms.GroupBox
$grpHost.Text = 'Connection details'
$grpHost.Location = New-Object System.Drawing.Point(44, 156); $grpHost.Size = New-Object System.Drawing.Size(580, 190)
$grpHost.Enabled = $false
$pgDb.Controls.Add($grpHost)
New-Label $grpHost 'Server address' 16 28 130 20 | Out-Null
$txtDbHost = New-TextBox $grpHost $S.DbHost 150 25 400
New-Label $grpHost 'Port' 16 58 130 20 | Out-Null
$txtDbPort = New-TextBox $grpHost $S.DbPort 150 55 100
New-Label $grpHost 'Database name' 16 88 130 20 | Out-Null
$txtDbName = New-TextBox $grpHost $S.DbName 150 85 250
New-Label $grpHost 'Login name' 16 118 130 20 | Out-Null
$txtDbUser = New-TextBox $grpHost $S.DbUser 150 115 250
New-Label $grpHost 'Password' 16 148 130 20 | Out-Null
$txtDbPass = New-TextBox $grpHost '' 150 145 250
$txtDbPass.UseSystemPasswordChar = $true

$rbDocker.Add_CheckedChanged({ $grpHost.Enabled = -not $rbDocker.Checked })

# ---------------------------------------------------------------------------
# Page 3 - organisation
# ---------------------------------------------------------------------------
$pgOrg = New-Page
New-Label $pgOrg 'About your organisation' 24 22 620 20 -Bold | Out-Null
New-Label $pgOrg 'Used once, to create your company and the first administrator. You can rename things later inside the system.' 24 46 620 36 -Dim | Out-Null
New-Label $pgOrg 'Organisation name' 24 96 160 20 | Out-Null
$txtOrg = New-TextBox $pgOrg $S.OrgName 190 93 400
New-Label $pgOrg 'Company name' 24 128 160 20 | Out-Null
$txtCompany = New-TextBox $pgOrg $S.CompanyName 190 125 400
New-Label $pgOrg 'Main branch' 24 160 160 20 | Out-Null
$txtBranch = New-TextBox $pgOrg $S.BranchName 190 157 400
New-Label $pgOrg 'Timezone' 24 192 160 20 | Out-Null
$cboTz = New-Object System.Windows.Forms.ComboBox
$cboTz.Location = New-Object System.Drawing.Point(190, 189); $cboTz.Size = New-Object System.Drawing.Size(400, 22)
$cboTz.Font = New-Object System.Drawing.Font('Segoe UI', 9)
@('Africa/Dar_es_Salaam','Africa/Nairobi','Africa/Kampala','Africa/Kigali','Africa/Lusaka','Africa/Johannesburg','Europe/London','UTC') |
    ForEach-Object { [void]$cboTz.Items.Add($_) }
$cboTz.Text = $S.TimeZone
$pgOrg.Controls.Add($cboTz)
New-Label $pgOrg 'Currency for the accounts' 24 224 160 32 | Out-Null
$cboCur = New-Object System.Windows.Forms.ComboBox
$cboCur.Location = New-Object System.Drawing.Point(190, 221); $cboCur.Size = New-Object System.Drawing.Size(120, 22)
$cboCur.Font = New-Object System.Drawing.Font('Segoe UI', 9)
@('TZS','KES','UGX','RWF','BIF','SSP','USD','EUR') | ForEach-Object { [void]$cboCur.Items.Add($_) }
$cboCur.Text = $S.Currency
$pgOrg.Controls.Add($cboCur)
New-Label $pgOrg 'The currency your accounts are kept in is difficult to change once trading begins. Take a moment over it.' 24 258 620 36 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Page 4 - network
# ---------------------------------------------------------------------------
$pgNet = New-Page
New-Label $pgNet 'How will people reach the system?' 24 22 620 20 -Bold | Out-Null
New-Label $pgNet 'Everyone uses it through a web browser. Nothing is installed on their computers.' 24 46 620 20 -Dim | Out-Null
New-Label $pgNet 'Port number' 24 92 160 20 | Out-Null
$txtPort = New-TextBox $pgNet $S.HttpPort 190 89 100
$lblPortState = New-Label $pgNet '' 300 92 340 20 -Dim
$chkLan = New-Object System.Windows.Forms.CheckBox
$chkLan.Text = 'Allow other computers on our network to use it'
$chkLan.Location = New-Object System.Drawing.Point(24, 132); $chkLan.Size = New-Object System.Drawing.Size(600, 22)
$chkLan.Checked = $true; $chkLan.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgNet.Controls.Add($chkLan)
New-Label $pgNet 'Leave this ticked for normal use. Untick it to keep the system usable only from this computer.' 44 154 580 20 -Dim | Out-Null

$chkShortcut = New-Object System.Windows.Forms.CheckBox
$chkShortcut.Text = 'Put a shortcut on the desktop'
$chkShortcut.Location = New-Object System.Drawing.Point(24, 192); $chkShortcut.Size = New-Object System.Drawing.Size(600, 22)
$chkShortcut.Checked = $true; $chkShortcut.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgNet.Controls.Add($chkShortcut)

$txtPort.Add_TextChanged({
    $n = 0
    if ([int]::TryParse($txtPort.Text, [ref]$n) -and $n -gt 0 -and $n -lt 65536) {
        if (Test-PortFree $n) {
            $lblPortState.Text = 'This port is free.'
            $lblPortState.ForeColor = [System.Drawing.Color]::FromArgb(20, 110, 50)
        } else {
            $lblPortState.Text = 'In use by another program - choose a different number.'
            $lblPortState.ForeColor = [System.Drawing.Color]::FromArgb(170, 30, 30)
        }
    } else {
        $lblPortState.Text = 'Enter a number between 1 and 65535.'
        $lblPortState.ForeColor = [System.Drawing.Color]::FromArgb(170, 30, 30)
    }
})

# ---------------------------------------------------------------------------
# Page 5 - review
# ---------------------------------------------------------------------------
$pgReview = New-Page
New-Label $pgReview 'Ready to install' 24 22 620 20 -Bold | Out-Null
New-Label $pgReview 'Check these details, then click Install. Nothing has been changed on this computer yet.' 24 46 620 20 -Dim | Out-Null
$txtReview = New-Object System.Windows.Forms.TextBox
$txtReview.Multiline = $true; $txtReview.ReadOnly = $true; $txtReview.ScrollBars = 'Vertical'
$txtReview.Location = New-Object System.Drawing.Point(24, 82)
$txtReview.Size = New-Object System.Drawing.Size(620, 300)
$txtReview.Font = New-Object System.Drawing.Font('Consolas', 9)
$txtReview.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$pgReview.Controls.Add($txtReview)

# ---------------------------------------------------------------------------
# Page 6 - progress
# ---------------------------------------------------------------------------
$pgWork = New-Page
New-Label $pgWork 'Installing' 24 22 620 20 -Bold | Out-Null
$lblStep = New-Label $pgWork 'Starting...' 24 46 620 20 -Dim
$bar = New-Object System.Windows.Forms.ProgressBar
$bar.Location = New-Object System.Drawing.Point(24, 74); $bar.Size = New-Object System.Drawing.Size(620, 18)
$bar.Minimum = 0; $bar.Maximum = 6
$pgWork.Controls.Add($bar)
$script:LogBox = New-Object System.Windows.Forms.TextBox
$script:LogBox.Multiline = $true; $script:LogBox.ReadOnly = $true; $script:LogBox.ScrollBars = 'Vertical'
$script:LogBox.Location = New-Object System.Drawing.Point(24, 104)
$script:LogBox.Size = New-Object System.Drawing.Size(620, 280)
$script:LogBox.Font = New-Object System.Drawing.Font('Consolas', 8)
$script:LogBox.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$pgWork.Controls.Add($script:LogBox)
New-Label $pgWork 'The first start creates the database structure and can take several minutes.' 24 392 620 20 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Page 7 - done
# ---------------------------------------------------------------------------
$pgDone = New-Page
New-Label $pgDone 'OrbixERP is installed and running' 24 22 620 24 -Bold | Out-Null
$txtDone = New-Object System.Windows.Forms.TextBox
$txtDone.Multiline = $true; $txtDone.ReadOnly = $true; $txtDone.ScrollBars = 'Vertical'
$txtDone.Location = New-Object System.Drawing.Point(24, 58)
$txtDone.Size = New-Object System.Drawing.Size(620, 250)
$txtDone.Font = New-Object System.Drawing.Font('Consolas', 9)
$txtDone.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$pgDone.Controls.Add($txtDone)

$btnCopy = New-Object System.Windows.Forms.Button
$btnCopy.Text = 'Copy password'; $btnCopy.Size = New-Object System.Drawing.Size(130, 30)
$btnCopy.Location = New-Object System.Drawing.Point(24, 318); $btnCopy.FlatStyle = 'System'
$pgDone.Controls.Add($btnCopy)
$btnCopy.Add_Click({
    [System.Windows.Forms.Clipboard]::SetText($S.AdminPass)
    $btnCopy.Text = 'Copied'
})

$btnOpen = New-Object System.Windows.Forms.Button
$btnOpen.Text = 'Open in browser'; $btnOpen.Size = New-Object System.Drawing.Size(130, 30)
$btnOpen.Location = New-Object System.Drawing.Point(164, 318); $btnOpen.FlatStyle = 'System'
$pgDone.Controls.Add($btnOpen)
$btnOpen.Add_Click({ Start-Process ("http://localhost:" + $S.HttpPort) })

New-Label $pgDone 'Back up these three together - a database backup alone cannot be signed into: the backups folder, the .env file, and the secrets folder.' 24 356 620 40 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Navigation
# ---------------------------------------------------------------------------
$pages = @(
    @{ Page = $pgWelcome; Title = 'Welcome';           Sub = 'Checking that this computer is ready' }
    @{ Page = $pgFolder;  Title = 'Installation folder'; Sub = 'Where the system will live' }
    @{ Page = $pgDb;      Title = 'Database';          Sub = 'Where your data is stored' }
    @{ Page = $pgOrg;     Title = 'Your organisation'; Sub = 'Used to create your company and first administrator' }
    @{ Page = $pgNet;     Title = 'Access';            Sub = 'How people will reach the system' }
    @{ Page = $pgReview;  Title = 'Review';            Sub = 'Confirm before anything is changed' }
    @{ Page = $pgWork;    Title = 'Installing';        Sub = 'This takes a few minutes' }
    @{ Page = $pgDone;    Title = 'Finished';          Sub = 'Write down the password below' }
)
$script:Index = 0

function Show-Page {
    param([int]$i)
    $script:Index = $i
    foreach ($p in $pages) { $p.Page.Visible = $false }
    $pages[$i].Page.Visible = $true
    $hTitle.Text = $pages[$i].Title
    $hSub.Text   = $pages[$i].Sub
    $btnBack.Enabled = ($i -gt 0 -and $i -lt 6)
    $btnNext.Enabled = $true
    $btnNext.Text = switch ($i) { 5 { 'Install' } 7 { 'Finish' } default { 'Next' } }
    $btnCancel.Enabled = ($i -lt 6)
    if ($i -eq 0) { Invoke-Checks }
    # Re-assigning the text fires TextChanged, which is what fills in the port availability
    # message the first time this page is shown.
    if ($i -eq 4) { $txtPort.Text = $S.HttpPort }
}

function Save-Page {
    param([int]$i)
    switch ($i) {
        1 {
            $path = $txtFolder.Text.Trim()
            if (-not $path) { [System.Windows.Forms.MessageBox]::Show('Choose a folder to install into.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false }
            if ($path -notmatch '^[A-Za-z]:\\') { [System.Windows.Forms.MessageBox]::Show("Enter a full path, for example C:\OrbixERP", 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false }

            # REFUSING HERE PREVENTS A REAL DISASTER. Installing over a working installation
            # would write a new .env with a NEW database password while the existing database
            # still expects the old one, and would replace the signing keys, logging every user
            # out. The result is a live system that stops working with no obvious cause.
            # Upgrades are 'erp update', which takes a backup first; this is not that.
            # An existing installation is the NORMAL case for a product under continuous
            # release: the client downloads a new bundle and double-clicks Setup again. So
            # offer to update it rather than refusing and sending them to a terminal. The one
            # thing never done is a fresh install over the top - that would write a new .env
            # with a new database password while the existing database expects the old one,
            # and replace the signing keys, logging every user out.
            if (Test-Path (Join-Path $path '.env')) {
                $installed = ''
                try {
                    $m = Select-String -Path (Join-Path $path '.env') -Pattern '^\s*ERP_VERSION=' | Select-Object -Last 1
                    if ($m) { $installed = ($m.Line -split '=', 2)[1].Trim() }
                } catch { }

                if ($installed -eq $S.Version) {
                    [System.Windows.Forms.MessageBox]::Show(
                        "Version $($S.Version) is already installed in that folder - it is the same " +
                        "version as this bundle, so there is nothing to update.`n`n" +
                        "To start, stop or back it up, use OrbixERP.cmd in that folder.",
                        'OrbixERP - already up to date', 'OK', 'Information') | Out-Null
                    return $false
                }

                $from = if ($installed) { "Version $installed" } else { 'An installation' }
                $r = [System.Windows.Forms.MessageBox]::Show(
                    "$from is already installed in:`n    $path`n`n" +
                    "This bundle is version $($S.Version).`n`n" +
                    "Update the existing installation to $($S.Version)?`n`n" +
                    "Your data, settings and security keys are kept. A backup is taken first, and " +
                    "the update stops if that backup fails.",
                    'OrbixERP - update available', 'YesNo', 'Question')
                if ($r -ne 'Yes') { return $false }

                $S.Mode = 'update'
                $S.InstalledVersion = $installed
                $S.InstallDir = $path
                return $true
            }
            $S.Mode = 'install'

            # System locations are refused outright - no "install anyway as administrator"
            # escape hatch, because taking it produces a system that needs administrator
            # rights forever after, for updates and even for backups.
            if (Test-SystemLocation $path) {
                $r = [System.Windows.Forms.MessageBox]::Show(
                    "OrbixERP cannot be installed in a Windows system folder:`n    $path`n`n" +
                    "It installs into its own folder instead, so that it needs no administrator " +
                    "rights and everything - settings, security keys and backups - stays together " +
                    "in one place you can copy or move.`n`n" +
                    "Use C:\OrbixERP instead?",
                    'OrbixERP - choose a different folder', 'YesNo', 'Warning')
                if ($r -eq 'Yes') { $txtFolder.Text = 'C:\OrbixERP' }
                return $false
            }

            if (Test-VolatileLocation $path) {
                $r = [System.Windows.Forms.MessageBox]::Show(
                    "That folder is one Windows empties or synchronises:`n    $path`n`n" +
                    "The install folder holds your settings, your security keys and your backups. " +
                    "Losing it means losing access to your data.`n`n" +
                    "Use it anyway? (Choose No to pick somewhere permanent.)",
                    'OrbixERP - risky location', 'YesNo', 'Warning')
                if ($r -ne 'Yes') { return $false }
            }

            # Anywhere else still has to be genuinely writable - a read-only share, a
            # disconnected drive letter, a Controlled Folder Access rule. Without this probe
            # the install fails several steps later on a raw .NET "Access to the path is
            # denied", by which point a folder exists and images have been loaded.
            if (-not (Test-FolderWritable $path)) {
                [System.Windows.Forms.MessageBox]::Show(
                    "Windows will not let this wizard write to:`n    $path`n`n" +
                    "Pick a folder you can write to. C:\OrbixERP works on any Windows computer and " +
                    "needs no special rights.",
                    'OrbixERP - folder cannot be used', 'OK', 'Warning') | Out-Null
                return $false
            }

            # A drive with no room produces a baffling failure deep inside `docker load`.
            try {
                $drive = [System.IO.DriveInfo]::New((Split-Path -Qualifier $path) + '\')
                if ($drive.IsReady -and $drive.AvailableFreeSpace -lt 2GB) {
                    $gb = [math]::Round($drive.AvailableFreeSpace / 1GB, 1)
                    $r = [System.Windows.Forms.MessageBox]::Show(
                        "Only $gb GB is free on that drive.`n`n" +
                        "The application images need roughly 1.5 GB once loaded, and the database " +
                        "grows from there. Continue anyway?",
                        'OrbixERP - low disk space', 'YesNo', 'Warning')
                    if ($r -ne 'Yes') { return $false }
                }
            } catch { }

            $S.InstallDir = $path

            # The instance name is derived from the folder, so installing a second copy
            # somewhere else automatically gets its own containers, network and database
            # rather than silently attaching to the first one's. No extra question to answer,
            # and the common case still produces plain "orbixerp".
            #
            #   C:\OrbixERP            -> orbixerp
            #   C:\OrbixERP-Training   -> orbixerp-training
            #   D:\Apps\Demo           -> orbixerp-demo
            #
            # The orbixerp- prefix is always present, so a folder called "ERP" cannot produce
            # containers named erp-* and collide with something else on the machine.
            $leaf = (Split-Path -Leaf $path).ToLower() -replace '[^a-z0-9]+', '-'
            $leaf = $leaf.Trim('-')
            if (-not $leaf) { $leaf = 'orbixerp' }
            if ($leaf -eq 'orbixerp') { $S.StackName = 'orbixerp' }
            elseif ($leaf -like 'orbixerp-*') { $S.StackName = $leaf }
            else { $S.StackName = "orbixerp-$leaf" }
        }
        2 {
            $S.DbMode = if ($rbDocker.Checked) { 'docker' } else { 'host' }
            $S.ExistingDb = ''

            # A surviving database from a previous installation. Handled HERE, before anything
            # is written, because installing over it silently produces a system that can never
            # authenticate - see Test-DbVolumeExists.
            #
            # THIS WIZARD NEVER DELETES A DATABASE. There is deliberately no "start fresh"
            # option, not even behind a confirmation: this is run by operators, on the machine
            # holding a business's entire accounting history, and one mis-clicked dialog must
            # not be able to destroy it. Reusing is the only automatic behaviour. Anyone who
            # genuinely wants an empty database has to remove it themselves, by hand, outside
            # the installer - which is documented, and which nobody does by accident.
            if ($S.DbMode -eq 'docker' -and (Test-DbVolumeExists $S.StackName)) {
                $r = [System.Windows.Forms.MessageBox]::Show(
                    "A database from a previous installation of OrbixERP is already on this " +
                    "computer.`n`n" +
                    "This installation will CONNECT TO IT and your existing data will be kept. " +
                    "Nothing in that database is deleted or replaced.`n`n" +
                    "    OK      -  Continue, keeping the existing data`n" +
                    "    CANCEL  -  Go back (for example to install a separate system in a " +
                    "different folder, which gets its own database)`n`n" +
                    "This wizard never deletes a database. If you truly need to start empty, " +
                    "see 'Starting again with an empty database' in docs\TROUBLESHOOTING.txt.",
                    'OrbixERP - existing database found', 'OKCancel', 'Information')

                if ($r -ne 'OK') { return $false }
                $S.ExistingDb = 'reuse'
            }

            if ($S.DbMode -eq 'host') {
                if (-not $txtDbPass.Text) { [System.Windows.Forms.MessageBox]::Show('Enter the password for the database login.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false }
                $S.DbHost = $txtDbHost.Text.Trim(); $S.DbPort = $txtDbPort.Text.Trim()
                $S.DbName = $txtDbName.Text.Trim(); $S.DbUser = $txtDbUser.Text.Trim()
                $S.DbPassword = $txtDbPass.Text
            }
        }
        3 {
            $S.OrgName = $txtOrg.Text.Trim(); $S.CompanyName = $txtCompany.Text.Trim()
            $S.BranchName = $txtBranch.Text.Trim(); $S.TimeZone = $cboTz.Text.Trim(); $S.Currency = $cboCur.Text.Trim().ToUpper()
        }
        4 {
            $n = 0
            if (-not ([int]::TryParse($txtPort.Text, [ref]$n)) -or $n -lt 1 -or $n -gt 65535) {
                [System.Windows.Forms.MessageBox]::Show('Enter a port number between 1 and 65535.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            if (-not (Test-PortFree $n)) {
                [System.Windows.Forms.MessageBox]::Show("Port $n is already used by another program on this computer. Choose a different number.", 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            $S.HttpPort = "$n"
            $S.BindAddr = if ($chkLan.Checked) { '0.0.0.0' } else { '127.0.0.1' }
            $S.Shortcut = $chkShortcut.Checked
        }
    }
    return $true
}

function Update-Review {
    if ($S.Mode -eq 'update') {
        $from = if ($S.InstalledVersion) { $S.InstalledVersion } else { 'an earlier version' }
        $txtReview.Text = @"
Update an existing installation

Folder              $($S.InstallDir)
Currently installed $from
Will become         $($S.Version)

What happens

  1. A backup of your database is taken FIRST.
     If that backup fails for any reason, the update stops and nothing changes.
  2. The new application is loaded.
  3. Your settings files are refreshed, and the system is restarted.

What is kept

  Your data, your settings (.env), your security keys and your existing backups
  are all left exactly as they are.

Going back

  Database changes only run forwards, so reinstalling the older version does NOT
  undo an update. The backup taken in step 1 is the only way back - its filename
  is shown when the update finishes. Keep it until you are satisfied.
"@ -replace "`n", "`r`n"
        return
    }

    $dbLine = if ($S.DbMode -eq 'docker') { 'Installed for you, stored on this computer' }
              else { "Your own server at $($S.DbHost):$($S.DbPort), database '$($S.DbName)'" }
    $lan = if ($S.BindAddr -eq '0.0.0.0') { 'Yes - other computers on the network can use it' } else { 'No - only this computer' }
    $txtReview.Text = @"
Install into        $($S.InstallDir)

Database            $dbLine
Web address         http://localhost:$($S.HttpPort)
Shared on network   $lan

Organisation        $($S.OrgName)
Company             $($S.CompanyName)
Main branch         $($S.BranchName)
Timezone            $($S.TimeZone)
Currency            $($S.Currency)

Version             $($S.Version) ($($S.BundleArch))

The administrator password will be generated and shown when this finishes.
"@ -replace "`n", "`r`n"
}

# ---------------------------------------------------------------------------
# The installation itself
# ---------------------------------------------------------------------------
function Set-EnvFile {
    $tpl = Join-Path $SourceDir '.env.example'
    if (-not (Test-Path $tpl)) { throw '.env.example is missing from the bundle.' }
    $values = @{
        ERP_DB_MODE                    = $S.DbMode
        ERP_DB_NAME                    = $S.DbName
        ERP_DB_USER                    = $S.DbUser
        ERP_DB_PASSWORD                = $S.DbPassword
        ERP_DB_HOST                    = $S.DbHost
        ERP_DB_PORT                    = $S.DbPort
        ERP_HTTP_PORT                  = $S.HttpPort
        ERP_BIND_ADDR                  = $S.BindAddr
        ERP_STACK_NAME                 = $S.StackName
        ERP_BOOTSTRAP_ENABLED          = 'true'
        ERP_BOOTSTRAP_ORG_NAME         = $S.OrgName
        ERP_BOOTSTRAP_COMPANY_NAME     = $S.CompanyName
        ERP_BOOTSTRAP_BRANCH_NAME      = $S.BranchName
        ERP_BOOTSTRAP_ADMIN_PASSWORD   = $S.AdminPass
        ERP_TIME_ZONE                  = $S.TimeZone
        ERP_BOOTSTRAP_TIME_ZONE        = $S.TimeZone
        ERP_BOOTSTRAP_CURRENCY_BASE    = $S.Currency
        ERP_BOOTSTRAP_CURRENCY_DEFAULT = $S.Currency
    }
    $lines = [System.IO.File]::ReadAllText($tpl) -split "`r?`n"
    $out = foreach ($line in $lines) {
        $replaced = $line
        foreach ($k in $values.Keys) {
            if ($line -match "^\s*$([regex]::Escape($k))=") { $replaced = "$k=$($values[$k])"; break }
        }
        $replaced
    }
    # LF endings, no byte-order mark: Docker passes each line into the container verbatim,
    # so a stray carriage return would silently corrupt every value.
    $target = Join-Path $S.InstallDir '.env'
    [System.IO.File]::WriteAllText($target, (($out -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

# In `host` mode the two things that go wrong are an unreachable server and wrong
# credentials. Left unchecked, both surface only as the API exiting and being restarted
# forever, which reads as an unexplained crash. Checked here, each gets a message naming
# the thing to fix. This runs after the images are loaded, because it needs the postgres
# image to do the checking.
function Test-HostDatabase {
    Write-Log "Checking your PostgreSQL server at $($S.DbHost):$($S.DbPort) ..."

    $reach = "run --rm --add-host host.docker.internal:host-gateway postgres:15-alpine " +
             "pg_isready -h $($S.DbHost) -p $($S.DbPort) -t 10"
    if ((Invoke-Tracked 'docker' $reach) -ne 0) {
        throw ("Cannot reach a PostgreSQL server at $($S.DbHost):$($S.DbPort).`r`n`r`n" +
               "On the database server check that PostgreSQL is running, that postgresql.conf has " +
               "listen_addresses = '*', that pg_hba.conf accepts connections from 172.16.0.0/12, " +
               "and that no firewall blocks the port.`r`n`r`n" +
               "docs\HOST-DB-SETUP.txt has the exact steps.")
    }

    # One query does double duty: it proves the credentials work AND counts what is already in
    # the database.
    $prefix = "run --rm --add-host host.docker.internal:host-gateway " +
              "-e PGPASSWORD=$($S.DbPassword) postgres:15-alpine " +
              "psql -h $($S.DbHost) -p $($S.DbPort) -U $($S.DbUser) -d $($S.DbName) -tAc "

    $count = Invoke-Capture 'docker' ($prefix + "`"select count(*) from information_schema.tables where table_schema = 'public'`"")
    if ($count.Code -ne 0) {
        throw ("The server answered, but database '$($S.DbName)' could not be opened as '$($S.DbUser)'.`r`n`r`n" +
               "Check that the database exists, that the login exists, that the password is right, " +
               "and that the login is allowed to connect to it.`r`n`r`n" +
               "docs\HOST-DB-SETUP.txt has the exact commands.")
    }

    if ($count.Out -eq '0') {
        Write-Log 'Your database is reachable, the login works, and the database is empty.'
        return
    }

    # Something is in there. If it is one of ours, this is a reinstall against an existing
    # database and the application will simply upgrade it. If it is not, stop: the schema tool
    # will not adopt a database it does not recognise, and a half-applied install into someone
    # else's data is far worse than refusing now.
    $ours = Invoke-Capture 'docker' ($prefix + "`"select to_regclass('public.flyway_schema_history') is not null`"")
    if ($ours.Code -eq 0 -and $ours.Out -eq 't') {
        Write-Log "Database '$($S.DbName)' already holds an OrbixERP installation - it will be reused, not replaced."
        return
    }

    throw ("Database '$($S.DbName)' already contains $($count.Out) table(s), and they were not " +
           "created by this system.`r`n`r`n" +
           "This application needs a database of its own. Installing into a database that holds " +
           "other data is not supported and would fail partway through.`r`n`r`n" +
           "Create an empty database for it - docs\HOST-DB-SETUP.txt has the commands - then run " +
           "Setup again.")
}

# The update itself is NOT reimplemented here. `orbixerp.ps1 update` already does it -
# safety backup first, abort if that backup fails, load images, refresh config, restart,
# wait for health, report the rollback file - and having one implementation means the
# graphical and typed routes cannot drift into behaving differently.
function Start-Update {
    Show-Page 6
    $script:Installing = $true
    $btnNext.Enabled = $false; $btnBack.Enabled = $false; $btnCancel.Enabled = $false
    $bar.Maximum = 3
    try {
        Assert-DockerAlive 'it was checked when the wizard started'

        $bar.Value = 1; $lblStep.Text = 'Backing up, then updating (do not close this window)...'
        Write-Log "Updating $($S.InstallDir) to version $($S.Version)"
        Write-Log ''

        $ps = Join-Path $S.InstallDir 'orbixerp.ps1'
        if (-not (Test-Path $ps)) { throw "That folder does not contain orbixerp.ps1, so it cannot be updated automatically." }

        $code = Invoke-Tracked 'powershell' "-NoProfile -ExecutionPolicy Bypass -File `"$ps`" update `"$SourceDir`"" $S.InstallDir
        if ($code -ne 0) { throw 'The update did not finish. The messages above explain why, and your safety backup is in the backups folder.' }

        $bar.Value = 2; $lblStep.Text = 'Refreshing the Windows shortcuts...'
        foreach ($f in @('Setup.cmd', 'setup-wizard.ps1', 'Install.cmd', 'OrbixERP.cmd')) {
            $from = Join-Path $SourceDir $f
            if (Test-Path $from) { Copy-Item $from (Join-Path $S.InstallDir $f) -Force }
        }

        $bar.Value = 3; $lblStep.Text = 'Done.'
        $script:Installing = $false

        $port = '8080'
        try {
            $m = Select-String -Path (Join-Path $S.InstallDir '.env') -Pattern '^\s*ERP_HTTP_PORT=' | Select-Object -Last 1
            if ($m) { $port = ($m.Line -split '=', 2)[1].Trim() }
        } catch { }
        $S.HttpPort = $port

        $backup = 'the newest file in the backups folder'
        try {
            $b = Get-ChildItem (Join-Path $S.InstallDir 'backups') -Filter '*.dump' -File |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($b) { $backup = "backups\$($b.Name)" }
        } catch { }

        $hTitle.Text = 'Updated'
        $txtDone.Text = @"
OrbixERP has been updated to version $($S.Version).

Open it in a browser

    on this computer    http://localhost:$port
    from the network    http://$($env:COMPUTERNAME):$port

Sign in exactly as before - your users, passwords and data are unchanged.

If anything is wrong with this version

    A backup was taken before the update:
        $backup

    Database changes only run forwards, so putting the older version back does
    NOT undo the update. Restoring that backup is the only way back:

        .\orbixerp.ps1 restore $backup

    Keep it until you are satisfied this version is behaving.
"@ -replace "`n", "`r`n"
        $btnCopy.Visible = $false
        Show-Page 7
        $hSub.Text = 'Your data and settings were kept'
    } catch {
        $script:Installing = $false
        $lblStep.Text = 'Update stopped.'
        Write-Log ''
        Write-Log "PROBLEM: $($_.Exception.Message)"
        Write-Log ''
        Write-Log 'Your existing installation has NOT been left half-updated: the update takes a'
        Write-Log 'backup before it changes anything, and stops at the first failure.'
        $btnCancel.Text = 'Close'; $btnCancel.Enabled = $true
    }
}

function Start-Install {
    Show-Page 6
    $script:Installing = $true
    $btnNext.Enabled = $false; $btnBack.Enabled = $false; $btnCancel.Enabled = $false
    try {
        # Docker was checked on the welcome page, possibly several minutes ago.
        Assert-DockerAlive 'it was checked when the wizard started'

        # 1. folder + files
        $bar.Value = 1; $lblStep.Text = 'Creating the folder and copying files...'
        Write-Log "Installing into $($S.InstallDir)"
        New-Item -ItemType Directory -Path $S.InstallDir -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $S.InstallDir 'backups') -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $S.InstallDir 'secrets\jwt') -Force | Out-Null
        # Everything except images\ - those are loaded into Docker below and are dead weight
        # afterwards, and copying 400 MB the client will never open helps nobody.
        Get-ChildItem $SourceDir -Force | Where-Object { $_.Name -ne 'images' } | ForEach-Object {
            Copy-Item $_.FullName -Destination $S.InstallDir -Recurse -Force
        }
        Write-Log 'Files copied.'

        # 2. images. Loaded BEFORE the database check and before .env is written, because the
        # check needs the postgres image and there is no point writing settings for a database
        # that cannot be reached.
        $bar.Value = 2; $lblStep.Text = 'Loading the application (this is the slow part)...'
        foreach ($f in (Get-ChildItem (Join-Path $SourceDir 'images') -File | Where-Object { $_.Name -like '*.tar*' })) {
            Write-Log "Loading $($f.Name)"
            if ((Invoke-Tracked 'docker' "load -i `"$($f.FullName)`"") -ne 0) {
                throw ("Could not load $($f.Name).`r`n`r`n" +
                       "The file may be damaged - check it against CHECKSUMS.txt - or Docker may have " +
                       "run out of disk space.")
            }
        }

        # 3. the client's own database, if that is what they chose
        $bar.Value = 3
        if ($S.DbMode -eq 'host') { $lblStep.Text = 'Checking your database...'; Test-HostDatabase }

        # 4. passwords and settings
        $bar.Value = 4; $lblStep.Text = 'Generating passwords and writing settings...'
        $S.AdminPass = New-Password 20
        if ($S.DbMode -eq 'docker') { $S.DbPassword = New-Password 32 }
        Set-EnvFile
        Write-Log 'Settings written to .env'

        # 5. signing keys
        $lblStep.Text = 'Creating security keys...'
        $keyDir = Join-Path $S.InstallDir 'secrets\jwt'
        # Never regenerate over an existing pair: new keys invalidate every session token and
        # log every user out. The folder guard should make this unreachable, but a key pair is
        # not something to overwrite on the strength of a guard elsewhere.
        if ((Test-Path (Join-Path $keyDir 'private.pem')) -and (Test-Path (Join-Path $keyDir 'public.pem'))) {
            Write-Log 'Existing security keys found - keeping them.'
        } else {
            $img = "orbixerp-api:$($S.Version)"
            $sh  = 'set -e; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /keys/private.pem 2>/dev/null; ' +
                   'openssl pkey -pubout -in /keys/private.pem -out /keys/public.pem; ' +
                   'chown 10001:10001 /keys/private.pem /keys/public.pem; chmod 600 /keys/private.pem; chmod 644 /keys/public.pem'
            if ((Invoke-Tracked 'docker' "run --rm --user 0 -v `"$keyDir`:/keys`" --entrypoint sh $img -c `"$sh`"") -ne 0) {
                throw 'Could not create the security keys.'
            }
            Write-Log 'Security keys created.'
        }

        # 5b. a database left over from a previous installation is RECONNECTED, never
        # replaced. There is no code path in this wizard that removes a database volume.
        if ($S.ExistingDb -eq 'reuse') {
            $lblStep.Text = 'Reconnecting to your existing database...'
            Reset-DbPassword $S.StackName $S.InstallDir $S.DbUser $S.DbName $S.DbPassword
            # The database already holds an organisation, so first-run setup must not fire.
            $envPath = Join-Path $S.InstallDir '.env'
            $t = [System.IO.File]::ReadAllText($envPath) -replace '(?m)^ERP_BOOTSTRAP_ENABLED=.*$', 'ERP_BOOTSTRAP_ENABLED=false'
            [System.IO.File]::WriteAllText($envPath, (($t -split "`r?`n") -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
            Write-Log 'Existing organisation and users kept - sign in with your previous credentials.'
        }

        # 6. start
        $bar.Value = 5; $lblStep.Text = 'Starting the system and preparing the database...'

        # The port was free on the Access page, but that was several minutes and a large image
        # load ago. Re-checked here so a clash reports itself in plain words rather than as a
        # Docker "bind: address already in use" further down.
        if (-not (Test-PortFree ([int]$S.HttpPort))) {
            throw ("Port $($S.HttpPort) has been taken by another program since you chose it.`r`n`r`n" +
                   "Close whatever started using it, or run Setup again and pick a different port.")
        }
        Assert-DockerAlive 'while starting the system'
        $code = Invoke-Tracked 'powershell' "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $S.InstallDir 'orbixerp.ps1')`" start" $S.InstallDir
        if ($code -ne 0) { throw 'The system did not start. The messages above explain why.' }

        # 7. shortcut + first-run flag
        $bar.Value = 6; $lblStep.Text = 'Finishing...'
        if ($S.Shortcut) {
            try {
                $ws = New-Object -ComObject WScript.Shell
                $lnk = $ws.CreateShortcut([System.IO.Path]::Combine([Environment]::GetFolderPath('Desktop'), 'OrbixERP.lnk'))
                $lnk.TargetPath = Join-Path $S.InstallDir 'OrbixERP.cmd'
                $lnk.WorkingDirectory = $S.InstallDir
                $lnk.Description = 'Start, stop and back up OrbixERP'
                $lnk.Save()
                Write-Log 'Desktop shortcut created.'
            } catch { Write-Log 'Could not create the desktop shortcut (not important).' }
        }
        # First-run setup has done its job; it must not run again on the next start.
        $envPath = Join-Path $S.InstallDir '.env'
        $t = [System.IO.File]::ReadAllText($envPath) -replace '(?m)^ERP_BOOTSTRAP_ENABLED=.*$', 'ERP_BOOTSTRAP_ENABLED=false'
        [System.IO.File]::WriteAllText($envPath, (($t -split "`r?`n") -join "`n"), (New-Object System.Text.UTF8Encoding($false)))

        $machine = $env:COMPUTERNAME

        # When an existing database was reused, the generated administrator password is
        # meaningless - the users already in that database are unchanged. Showing it would be
        # actively misleading.
        if ($S.ExistingDb -eq 'reuse') {
            $btnCopy.Visible = $false
            $txtDone.Text = @"
Your existing data was kept.

Open it in a browser

    on this computer    http://localhost:$($S.HttpPort)
    from the network    http://$machine`:$($S.HttpPort)

Sign in with the SAME username and password you used before.

    No new administrator password has been created, because the accounts already
    in your database are unchanged.

If you cannot sign in

    The security keys in secrets\jwt\ are new, so anyone still logged in on
    another computer is signed out and simply needs to sign in again.

    If your old password is not accepted at all, the database may belong to a
    different installation than you expected - contact your supplier before
    making further changes.

Guides are in $($S.InstallDir)\docs - each as .txt and .md
"@ -replace "`n", "`r`n"
            $hSub.Text = 'Your existing database was reconnected'
            Show-Page 7
            return
        }

        $txtDone.Text = @"
Open it in a browser

    on this computer    http://localhost:$($S.HttpPort)
    from the network    http://$machine`:$($S.HttpPort)

Sign in with

    username            rootadmin
    password            $($S.AdminPass)

CHANGE THAT PASSWORD after your first sign-in.
It is also saved in $($S.InstallDir)\.env

From now on, use OrbixERP shortcut (or OrbixERP.cmd in the folder above)
to start, stop, check and back up the system.

Guides are in $($S.InstallDir)\docs - each as .txt and .md
"@ -replace "`n", "`r`n"
        $script:Installing = $false
        Show-Page 7
    } catch {
        $script:Installing = $false
        $lblStep.Text = 'Installation stopped.'
        Write-Log ''
        Write-Log "PROBLEM: $($_.Exception.Message)"
        Write-Log ''
        Write-Log 'Nothing further was changed. See docs\TROUBLESHOOTING.txt, fix the problem,'
        Write-Log 'and run Setup again - it is safe to repeat.'
        $btnCancel.Text = 'Close'; $btnCancel.Enabled = $true
    }
}

# ---------------------------------------------------------------------------
$btnNext.Add_Click({
    if ($script:Index -eq 7) { $form.Close(); return }
    if ($script:Index -eq 5) {
        if ($S.Mode -eq 'update') { Start-Update } else { Start-Install }
        return
    }
    if (-not (Save-Page $script:Index)) { return }

    # Updating skips the database, organisation and access pages entirely - all of that is
    # already configured in the installation being updated, and asking again would invite
    # someone to change it by accident.
    if ($script:Index -eq 1 -and $S.Mode -eq 'update') { Update-Review; Show-Page 5; return }

    if ($script:Index -eq 4) { Update-Review }
    Show-Page ($script:Index + 1)
})
$btnBack.Add_Click({
    if ($script:Index -eq 5 -and $S.Mode -eq 'update') { Show-Page 1; return }
    if ($script:Index -gt 0) { Show-Page ($script:Index - 1) }
})
$btnCancel.Add_Click({ $form.Close() })

# Cancel is disabled while installing, but the X in the title bar is not - and closing the
# window mid-way kills the wizard between steps, leaving a folder that is neither installed
# nor clean. Ask first; the work carries on if they say no.
$script:Installing = $false
$form.Add_FormClosing({
    # Not named $sender - that is a PowerShell automatic variable.
    param($src, $ev)
    if ($script:Installing) {
        $r = [System.Windows.Forms.MessageBox]::Show(
            "The installation is still running.`n`nClosing now would leave it half-finished, and you " +
            "would have to delete the folder before trying again.`n`nClose anyway?",
            'OrbixERP - installation in progress', 'YesNo', 'Warning')
        if ($r -ne 'Yes') { $ev.Cancel = $true }
    }
})

Show-Page 0
[void]$form.ShowDialog()
