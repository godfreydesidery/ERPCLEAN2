<#
    OrbixERP - graphical setup wizard for a REMOTE LINUX SERVER, driven from Windows.

    Started by double-clicking Remote-Setup.cmd. Not meant to be run directly, because
    Windows blocks PowerShell scripts that arrived from another computer; Remote-Setup.cmd
    lifts that block for this one script.

    It is the sibling of setup-wizard.ps1. That one installs on THIS computer; this one
    installs on a server you reach over SSH - and then keeps managing it: status, backup,
    update, restart, stop and recent logs, without the operator ever opening a terminal.

    What it does NOT do is reimplement the installation. The bundle already contains
    install.sh and orbixerp.sh, which are the tested implementation of installing,
    starting, backing up and updating. This wizard uploads the bundle and DRIVES THOSE
    SCRIPTS over SSH. One implementation means the graphical and typed routes cannot
    drift into behaving differently.

    Built on WinForms and the OpenSSH client that ships inside Windows 10/11: no runtime
    to install, no PuTTY, no WSL, nothing downloaded. The server needs no internet access
    either - the whole bundle is uploaded from this PC.

    Three rules run through the whole file and are worth knowing before changing it:

      1. HOST KEYS ARE CONFIRMED BY A HUMAN. StrictHostKeyChecking is never disabled and
         no key is ever added silently. On a first connection the fingerprint is shown and
         the operator says yes or no. A deploy tool that accepts any host key is a
         man-in-the-middle waiting to happen.

      2. SECRETS NEVER APPEAR IN ARGV, IN A FILE ON THIS PC, OR IN THE LOG. The database
         password, the first administrator password and the sudo password travel over the
         ssh process's standard input, from memory. Anything placed in a command line is
         visible to every user on the machine through the process list.

      3. EVERY REMOTE COMMAND HAS ITS EXIT STATUS CHECKED. A step that fails stops the
         wizard with a sentence saying what failed and what to do about it.

    ASCII only, deliberately. Windows PowerShell 5.1 reads a file without a byte-order
    mark as CP1252, and a UTF-8 dash would decode into a curly quote, which PowerShell
    treats as a string delimiter - breaking the script on the client's machine while
    looking perfect here.
#>

# -DryRun builds every ssh, scp and ssh-keygen command line this wizard can issue and
# prints them WITHOUT connecting to anything. It exists so the command construction - the
# part that cannot be exercised without a real server - is provable on a developer's
# machine. It opens no window and changes nothing.
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$SourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------------------------------------------------------------------
# State gathered across the pages
# ---------------------------------------------------------------------------
$S = @{
    # --- the server ---------------------------------------------------------
    SshHost      = ''
    SshPort      = '22'
    SshUser      = 'ubuntu'
    KeyFile      = ''
    RemoteDir    = '/opt/orbixerp'

    # --- what we learned about it (filled in by the checks page) -------------
    ServerArch       = ''
    ServerOs         = ''
    HasDocker        = $false
    HasCompose       = $false
    SudoMode         = 'none'      # nopass | password | none
    FreeKb           = 0
    InstalledVersion = ''

    # --- the installation ---------------------------------------------------
    DbName       = 'erp'
    DbUser       = 'erp'
    DbPassword   = ''
    HttpPort     = '8080'
    BindAddr     = '0.0.0.0'
    StackName    = 'orbixerp'
    PublicHost   = ''
    Tls          = 'off'           # off | self | letsencrypt
    OrgName      = 'My Organisation'
    CompanyName  = 'My Company'
    BranchName   = 'Head Office'
    TimeZone     = 'Africa/Dar_es_Salaam'
    Currency     = 'TZS'
    AdminPass    = ''
    Version      = ''
    BundleArch   = ''

    # 'install' on a server with nothing on it, 'update' to lift an existing installation
    # onto this bundle's version, 'manage' to run day-to-day jobs against it.
    Mode         = 'install'
}

# The sudo password, when the server needs one, lives here as a SecureString for as long
# as the wizard runs and is never written anywhere. See Send-SudoPassword.
$script:SudoSecret = $null

# ---------------------------------------------------------------------------
# Helpers - the same names and idioms as setup-wizard.ps1, so the two files read
# as one product.
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
# before anything has been uploaded and never flashes a console window.
function New-Password {
    param([int]$Length = 20)
    $alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object 'byte[]' $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

# The one door every line of output goes through - which is what makes the redaction below
# a guarantee rather than a hope.
#
# install.sh prints the first administrator password in its closing summary, and it is that
# script the wizard drives and streams. The password IS shown to the operator, but on the
# finished page where it is labelled and can be copied - not buried in a scrolling log that
# somebody may screenshot for support. The database password should never appear at all.
# Rather than audit every script for what it might print, the values are masked here on the
# way out, so anything that ever prints one is covered.
function Write-Log {
    param([string]$Text)
    foreach ($secret in @($S.DbPassword, $S.AdminPass)) {
        if ($secret -and $secret.Length -ge 8 -and $Text.Contains($secret)) {
            $Text = $Text.Replace($secret, '<password hidden - it is shown on the last page>')
        }
    }
    if ($null -eq $script:LogBox) {
        # Before the window exists (and under -DryRun there is no window at all).
        Write-Host $Text
        return
    }
    $script:LogBox.AppendText($Text.TrimEnd() + "`r`n")
    $script:LogBox.SelectionStart = $script:LogBox.TextLength
    $script:LogBox.ScrollToCaret()
    [System.Windows.Forms.Application]::DoEvents()
}

# Reads a file that another process is CURRENTLY WRITING. Get-Content would open it with
# FileShare.Read, which forbids the writer's own write access and fails intermittently
# with "the process cannot access the file"; asking for FileShare.ReadWrite explicitly is
# what makes tailing a live log reliable.
function Read-SharedLines {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return @() }
    $fs = $null
    try {
        $fs = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::Open,
                  [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $sr = New-Object System.IO.StreamReader($fs)
        $text = $sr.ReadToEnd()
        $sr.Close()
        return @($text -split "`r?`n")
    } catch {
        return @()
    } finally {
        if ($fs) { $fs.Dispose() }
    }
}

function Show-NewLines {
    param([string]$Path, [int]$AlreadyShown)
    $lines = Read-SharedLines $Path
    # The final element after a split is the incomplete line still being written; leave it
    # until the newline arrives, or the log stutters mid-word.
    $complete = $lines.Count - 1
    if ($complete -gt $AlreadyShown) {
        for ($i = $AlreadyShown; $i -lt $complete; $i++) { Write-Log $lines[$i] }
        return $complete
    }
    return $AlreadyShown
}

# ---------------------------------------------------------------------------
# Running things
#
# Everything - ssh, scp, ssh-keyscan, ssh-keygen, icacls - goes through this one
# function. It differs from setup-wizard.ps1's Invoke-Tracked in exactly one way that
# matters: the child's STANDARD INPUT is a pipe this wizard writes into, which is how the
# remote shell script, the file bytes of a 350 MB upload, and every secret get to the
# server without ever appearing in a command line or in a file on this PC.
#
# Note the deliberate use of System.Diagnostics.Process rather than Start-Process:
# Start-Process can only redirect standard input FROM A FILE, and secrets must not touch
# the disk. (The $p.Handle trick documented in setup-wizard.ps1 is not needed here -
# .ExitCode is reliable on a Process object we created and hold ourselves.)
#
# The child's output is copied to a temp file by .NET on a background thread and tailed
# from the foreground, so the window keeps repainting through operations that take
# minutes and the operator can see progress as it happens.
# ---------------------------------------------------------------------------
function Invoke-Piped {
    param(
        [string]$File,
        [string]$Arguments,
        [scriptblock]$WriteStdin = $null,
        [switch]$Quiet,
        # Called about once a second with the seconds elapsed so far. Some remote steps are
        # deliberately quiet for minutes at a time - install.sh prints a row of dots with no
        # newline while the database is being built, and a line that never completes is a
        # line this function cannot print. Without a tick the window looks frozen exactly
        # when the operator is most inclined to close it.
        [scriptblock]$OnTick = $null
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $File
    $psi.Arguments              = $Arguments
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true

    $outPath = [System.IO.Path]::GetTempFileName()
    $errPath = [System.IO.Path]::GetTempFileName()
    # Buffer size 1: the point of these files is to be readable WHILE they are written.
    $outFs = New-Object System.IO.FileStream($outPath, [System.IO.FileMode]::Create,
                 [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 1)
    $errFs = New-Object System.IO.FileStream($errPath, [System.IO.FileMode]::Create,
                 [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 1)

    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()

    # Started BEFORE anything is written to stdin. A child that fills its output pipe
    # while nobody is draining it blocks forever, and so would we.
    $outCopy = $p.StandardOutput.BaseStream.CopyToAsync($outFs, 4096)
    $errCopy = $p.StandardError.BaseStream.CopyToAsync($errFs, 4096)

    try {
        if ($WriteStdin) { & $WriteStdin $p }
    } finally {
        try { $p.StandardInput.Close() } catch { }
    }

    $shown = 0
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $ticked = -1
    while (-not $p.HasExited) {
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 150
        if (-not $Quiet) { $shown = Show-NewLines $outPath $shown }
        if ($OnTick) {
            $secs = [int]$clock.Elapsed.TotalSeconds
            if ($secs -ne $ticked) { $ticked = $secs; & $OnTick $secs }
        }
    }
    $p.WaitForExit()
    [void]$outCopy.Wait(10000)
    [void]$errCopy.Wait(10000)
    $outFs.Dispose(); $errFs.Dispose()

    $out = ''
    $err = ''
    try { $out = [System.IO.File]::ReadAllText($outPath) } catch { }
    try { $err = [System.IO.File]::ReadAllText($errPath) } catch { }
    if (-not $Quiet) {
        # The tail loop deliberately holds back the last line while it may still be
        # incomplete. Now the process has gone, whatever is there is all there is.
        $all = Read-SharedLines $outPath
        for ($i = $shown; $i -lt $all.Count; $i++) { if ($all[$i].Trim()) { Write-Log $all[$i] } }
        if ($err -and $err.Trim()) { Write-Log $err.Trim() }
    }
    $code = $p.ExitCode
    $p.Dispose()
    Remove-Item $outPath, $errPath -Force -ErrorAction SilentlyContinue
    return @{ Code = $code; Out = $out; Err = $err }
}

# Short local commands whose OUTPUT is the answer (ssh-keygen, ssh-keyscan, icacls).
# setup-wizard.ps1 has an Invoke-Capture that strips all whitespace, which suits a
# one-word answer from psql; host keys are multi-line, so this one returns the text as it
# came and lets the caller trim.
function Invoke-CaptureText {
    param([string]$File, [string]$Arguments)
    return (Invoke-Piped $File $Arguments $null -Quiet)
}

# ---------------------------------------------------------------------------
# The OpenSSH client
#
# Resolved to the copy Windows ships, by full path, rather than to whatever "ssh" happens
# to mean on this PATH. Git for Windows and several VPN clients put their own ssh.exe
# ahead of it, and those builds keep their known_hosts somewhere else entirely - so the
# host key the operator confirmed in this wizard would not be the file the next command
# reads.
# ---------------------------------------------------------------------------
function Get-OpenSshTool {
    param([string]$Name)
    $candidates = @(
        (Join-Path $env:SystemRoot "System32\OpenSSH\$Name"),
        # A 32-bit PowerShell sees System32 redirected to SysWOW64; Sysnative is the way back.
        (Join-Path $env:SystemRoot "Sysnative\OpenSSH\$Name")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return ''
}

$script:SshExe     = Get-OpenSshTool 'ssh.exe'
$script:ScpExe     = Get-OpenSshTool 'scp.exe'
$script:KeygenExe  = Get-OpenSshTool 'ssh-keygen.exe'
$script:KeyscanExe = Get-OpenSshTool 'ssh-keyscan.exe'

function Get-KnownHostsFile {
    return (Join-Path $env:USERPROFILE '.ssh\known_hosts')
}

# The host as it is written in known_hosts: bare for port 22, bracketed otherwise. Getting
# this wrong makes the wizard ask for confirmation of a key that is already trusted.
function Get-KnownHostsTarget {
    if ($S.SshPort -eq '22') { return $S.SshHost }
    return "[$($S.SshHost)]:$($S.SshPort)"
}

# The options every connection carries. Each one is here for a reason:
#
#   BatchMode=yes           never prompt for anything. Without it a server that wants a
#                           password would hang the wizard on an invisible prompt.
#   StrictHostKeyChecking=yes  refuse an unknown or changed host key. NEVER set this to
#                           'no' or 'accept-new' - see Confirm-HostKey.
#   IdentitiesOnly=yes      offer ONLY the key file chosen here. Otherwise ssh offers
#                           every key in the agent first and can exhaust the server's
#                           MaxAuthTries before reaching the right one.
#   UserKnownHostsFile      pinned so this wizard and Confirm-HostKey agree on which file
#                           they are talking about.
#   ServerAlive*            a 350 MB upload over a poor link must not be dropped by an
#                           idle NAT gateway.
function Get-CommonSshOptions {
    return @(
        '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'IdentitiesOnly=yes',
        '-o', 'ConnectTimeout=15',
        '-o', 'ServerAliveInterval=30',
        '-o', 'ServerAliveCountMax=6',
        '-o', ('UserKnownHostsFile="' + (Get-KnownHostsFile) + '"')
    )
}

# Builds the ssh argument string. $Remote is placed in the command line, so it must NEVER
# contain a secret - everything sensitive goes through stdin instead.
function Get-SshArgs {
    param([string]$Remote = '')
    $a = @('-i', ('"' + $S.KeyFile + '"'), '-p', $S.SshPort)
    $a += Get-CommonSshOptions
    $a += ("$($S.SshUser)@$($S.SshHost)")
    if ($Remote) { $a += $Remote }
    return ($a -join ' ')
}

# scp takes -P for the port where ssh takes -p. A perennial source of "connection refused"
# against servers on a non-standard port.
function Get-ScpArgs {
    param([string[]]$Sources, [string]$TargetDir)
    $a = @('-i', ('"' + $S.KeyFile + '"'), '-P', $S.SshPort, '-r', '-p')
    $a += Get-CommonSshOptions
    # NOT $s. PowerShell variable names are case-insensitive, so a loop variable called $s
    # shadows $S - the wizard's whole state - and the target below quietly loses its user
    # and host, producing "scp ... @:/opt/orbixerp/". Found by the dry run, which is
    # precisely what the dry run is for.
    foreach ($src in $Sources) { $a += ('"' + $src + '"') }
    $a += ("$($S.SshUser)@$($S.SshHost):'" + $TargetDir.TrimEnd('/') + "/'")
    return ($a -join ' ')
}

# A remote path is pasted into a shell command line, so it is validated rather than
# trusted: absolute, no spaces, no quotes, no shell metacharacters, no parent traversal.
# Everything the wizard then wraps in single quotes is provably safe.
function Test-RemotePath {
    param([string]$Path)
    if ($Path -notmatch '^/[A-Za-z0-9._/-]*$') { return $false }
    if ($Path -match '\.\.') { return $false }
    if ($Path -match '//') { return $false }
    if ($Path.TrimEnd('/').Length -lt 2) { return $false }
    return $true
}

function Test-RemoteUser {
    param([string]$Name)
    return ($Name -match '^[A-Za-z0-9._-]+$')
}

# The same directories install.sh refuses, checked HERE as well - and this is not
# belt-and-braces, it prevents a hang.
#
# install.sh is driven with --defaults, and in that mode its `ask` helper answers every
# question with the default instead of reading the terminal. Its install-location loop
# rejects a system or volatile directory and asks again... and is handed the same default
# again, forever. There is no terminal on the far end to interrupt it, so the wizard would
# sit watching a connection that never returns.
#
# Catching it on this side turns an unkillable wait into a sentence the operator can act on.
function Test-RemoteDirSafe {
    param([string]$Path)
    $p = $Path.TrimEnd('/')
    # Exactly the two lists install.sh uses, so the two cannot disagree about what is refused.
    $exact = @('', '/bin', '/sbin', '/lib', '/lib64', '/usr', '/usr/bin', '/usr/sbin', '/usr/lib',
               '/etc', '/boot', '/proc', '/sys', '/dev', '/run', '/var', '/root')
    if ($exact -contains $p) {
        return @{ Ok = $false; Reason = "$Path is a system directory. Install OrbixERP into a folder of its own, such as /opt/orbixerp." }
    }
    foreach ($s in @('/bin', '/sbin', '/lib', '/lib64', '/usr/bin', '/usr/sbin', '/usr/lib',
                     '/etc', '/boot', '/proc', '/sys', '/dev')) {
        if ($p.StartsWith($s + '/')) {
            return @{ Ok = $false; Reason = "$s is part of the Linux system itself. Install OrbixERP into a folder of its own, such as /opt/orbixerp." }
        }
    }
    foreach ($v in @('/tmp','/var/tmp')) {
        if ($p -eq $v -or $p.StartsWith($v + '/')) {
            return @{ Ok = $false; Reason = "$v is emptied when the server restarts, and the install folder holds your settings, your security keys and your backups. Choose somewhere permanent, such as /opt/orbixerp." }
        }
    }
    return @{ Ok = $true; Reason = '' }
}

# ---------------------------------------------------------------------------
# Talking to the server
# ---------------------------------------------------------------------------

# Runs a shell script on the server. The script travels over standard input to `bash -s`,
# never in the command line: it can therefore be as long and as quoted as it likes, and
# nothing in it is visible in the server's process list.
#
# -AllowFailure is for the checks page, which REPORTS a failure to connect as a red row
# rather than tearing the wizard down: a wrong user name or the wrong key file is the most
# ordinary thing in the world on a first attempt, and it must be correctable by clicking
# Back, not by restarting.
#
# -OnTick is passed straight through to Invoke-Piped for the long-running steps.
function Invoke-Remote {
    param([string]$Script, [switch]$Sudo, [switch]$Quiet, [switch]$AllowFailure, [scriptblock]$OnTick = $null)

    $remote = 'bash -s'
    if ($Sudo) {
        if ($S.SudoMode -eq 'nopass') {
            $remote = 'sudo -n bash -s'
        } elseif ($S.SudoMode -eq 'password') {
            # -S makes sudo read the password from standard input instead of a terminal,
            # and -p '' suppresses the prompt text. sudo consumes exactly one line and
            # leaves the rest of the pipe - the script - for bash.
            $remote = "sudo -S -p '' bash -s"
        } else {
            throw 'This step needs administrator rights on the server, but this account cannot use sudo.'
        }
    }

    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Script -replace "`r`n", "`n"))
    $sudoNeeded = ($Sudo -and $S.SudoMode -eq 'password')

    $r = Invoke-Piped $script:SshExe (Get-SshArgs $remote) {
        param($p)
        if ($sudoNeeded) { Send-SudoPassword $p }
        $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $p.StandardInput.BaseStream.Flush()
    } -Quiet:$Quiet -OnTick $OnTick

    if ($r.Code -eq 255 -and -not $AllowFailure) {
        # 255 is ssh's own failure, not the remote command's.
        throw (Get-ConnectionProblem $r)
    }
    return $r
}

# Turns ssh's own stderr into something an operator can act on. The host-key case is
# singled out because it is the one message here that may mean something is genuinely
# wrong rather than merely misconfigured, and OpenSSH's own wording ("POSSIBLE DNS
# SPOOFING", "Offending key in ...") is alarming without saying what to do.
function Get-ConnectionProblem {
    param($Result)
    $err = ''
    if ($Result -and $Result.Err) { $err = $Result.Err.Trim() }

    if ($err -match 'REMOTE HOST IDENTIFICATION HAS CHANGED|HOST KEY VERIFICATION FAILED|Host key verification failed') {
        return ("The server at $($S.SshHost) is not the one this computer trusted before.`r`n`r`n" +
                "Its identity key has changed. There are two innocent explanations - the server was " +
                "rebuilt or replaced, or the address now points at a different machine - and one that " +
                "is not innocent: something may be sitting between this computer and your server.`r`n`r`n" +
                "NOTHING HAS BEEN SENT. Do not continue until you know which it is.`r`n`r`n" +
                "If you rebuilt the server, remove its old entry and the wizard will ask you to confirm " +
                "the new identity next time:`r`n`r`n" +
                "    ssh-keygen -R " + (Get-KnownHostsTarget) + "`r`n`r`n" +
                "If you did not rebuild it, stop and ask whoever looks after the server.")
    }
    if ($err -match 'Permission denied') {
        return ("The server at $($S.SshHost) refused this key for the user '$($S.SshUser)'.`r`n`r`n" +
                "Check the user name (Ubuntu servers use 'ubuntu', Amazon Linux uses 'ec2-user', " +
                "Debian uses 'admin' or 'debian') and that the key file is the one issued for THIS " +
                "server. A key protected by a passphrase cannot be used here.`r`n`r`n" + $err)
    }
    return ("Lost the connection to $($S.SshHost).`r`n`r`n" +
            "Check the server is still running and reachable, then try again. " +
            "Nothing is left half-done on the server: each step is checked before the next one starts.`r`n`r`n" +
            $err)
}

# The sudo password is held as a SecureString and turned back into characters only for the
# instant it is written into the ssh pipe. It is never logged, never written to a file and
# never placed in a command line.
function Send-SudoPassword {
    param($Process)
    if ($null -eq $script:SudoSecret) { throw 'No administrator password was provided for the server.' }
    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($script:SudoSecret)
    try {
        $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)
        $b = [System.Text.Encoding]::UTF8.GetBytes($plain + "`n")
        $Process.StandardInput.BaseStream.Write($b, 0, $b.Length)
        $Process.StandardInput.BaseStream.Flush()
        # Wipe the copy we made. (The .NET string itself cannot be zeroed - see the note in
        # docs/REMOTE-INSTALL.md about what this does and does not protect.)
        [Array]::Clear($b, 0, $b.Length)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($ptr)
    }
}

# Writes text into a file on the server without that text ever existing on this PC as a
# file or as part of a command line. Used for .env, which holds both passwords.
#
# umask 077 before the redirect, so the file is created readable only by its owner - not
# created world-readable and tightened afterwards, which leaves a window open.
function Send-RemoteText {
    param([string]$Text, [string]$RemoteFile)
    if (-not (Test-RemotePath $RemoteFile)) { throw "Refusing to write to an unexpected path: $RemoteFile" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Text -replace "`r`n", "`n"))
    $cmd = "umask 077; cat > '$RemoteFile'"
    $r = Invoke-Piped $script:SshExe (Get-SshArgs ('"' + $cmd + '"')) {
        param($p)
        $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $p.StandardInput.BaseStream.Flush()
    } -Quiet
    [Array]::Clear($bytes, 0, $bytes.Length)
    if ($r.Code -ne 0) { throw "Could not write $RemoteFile on the server. $($r.Err.Trim())" }
}

# Uploads one file by streaming it into `cat` on the far end.
#
# scp would do this in one line, but scp's progress meter only appears when it is attached
# to a real console - redirected, it prints nothing for the twenty minutes a 350 MB image
# takes, and a wizard that shows nothing for twenty minutes reads as a crashed one.
# Streaming the bytes ourselves means the progress bar, the transfer rate and the estimate
# are all real numbers taken from the bytes actually handed to the connection.
function Send-RemoteFile {
    param([string]$LocalPath, [string]$RemoteFile, [scriptblock]$OnProgress = $null)
    if (-not (Test-RemotePath $RemoteFile)) { throw "Refusing to write to an unexpected path: $RemoteFile" }

    $total = (Get-Item $LocalPath).Length
    $cmd = "cat > '$RemoteFile'"
    $r = Invoke-Piped $script:SshExe (Get-SshArgs ('"' + $cmd + '"')) {
        param($p)
        $fs = [System.IO.File]::OpenRead($LocalPath)
        try {
            $buf = New-Object 'byte[]' 262144
            $sent = 0
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $lastPaint = 0
            while ($true) {
                $n = $fs.Read($buf, 0, $buf.Length)
                if ($n -le 0) { break }
                $p.StandardInput.BaseStream.Write($buf, 0, $n)
                $sent += $n
                # Repaint about five times a second. More often wastes the link's time on
                # drawing; less often looks stalled.
                if ($OnProgress -and ($sw.ElapsedMilliseconds - $lastPaint) -ge 200) {
                    $lastPaint = $sw.ElapsedMilliseconds
                    & $OnProgress $sent $total $sw.Elapsed.TotalSeconds
                }
            }
            $p.StandardInput.BaseStream.Flush()
            if ($OnProgress) { & $OnProgress $total $total $sw.Elapsed.TotalSeconds }
        } finally {
            $fs.Dispose()
        }
    } -Quiet

    if ($r.Code -ne 0) {
        throw ("The upload of " + (Split-Path -Leaf $LocalPath) + " did not complete.`r`n`r`n" +
               "The connection may have dropped, or the server may have run out of disk space. " +
               "It is safe to run this wizard again.`r`n`r`n" + $r.Err.Trim())
    }
}

function Format-Size {
    param([double]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N1} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N0} MB' -f ($Bytes / 1MB)) }
    return ('{0:N0} KB' -f ($Bytes / 1KB))
}

# Checked again before each phase of the work, so a server that reboots or a laptop that
# loses its wifi produces "the connection to the server was lost" instead of an unexplained
# failure inside whatever command happened to run next.
function Assert-ServerAlive {
    param([string]$When = 'now')
    $r = Invoke-Remote 'echo alive' -Quiet
    if ($r.Code -ne 0 -or $r.Out -notmatch 'alive') {
        throw ("The connection to $($S.SshHost) is no longer working ($When).`r`n`r`n" +
               "It answered when this wizard started, so the server has been stopped or the network " +
               "has changed since. Check the server, then run this wizard again - it is safe to repeat.")
    }
}

# ---------------------------------------------------------------------------
# Host key confirmation
#
# This is the security heart of the wizard, so it is worth being explicit about what is
# going on. The first time you connect to a server, SSH has no way of knowing whether the
# machine answering is really yours. It can only show you the fingerprint of the key that
# answered. If somebody is sitting between you and your server, THIS is the moment they
# are caught - and only if a human compares the fingerprint against a value obtained some
# other way (the cloud console's system log, or reading it off the server itself).
#
# Tools that set StrictHostKeyChecking=no skip that comparison for everybody, forever.
# This wizard therefore:
#   - looks the server up in known_hosts first, and says nothing if it is already trusted
#   - otherwise fetches the offered keys with ssh-keyscan, shows their fingerprints, and
#     waits for the operator to confirm
#   - writes to known_hosts only after that confirmation
# ---------------------------------------------------------------------------
function Test-HostKeyKnown {
    $target = Get-KnownHostsTarget
    $kh = Get-KnownHostsFile
    if (-not $script:KeygenExe) { return $false }
    if (-not (Test-Path $kh)) { return $false }
    $r = Invoke-CaptureText $script:KeygenExe ('-F "' + $target + '" -f "' + $kh + '"')
    return [bool](($r.Code -eq 0) -and $r.Out.Trim())
}

function Confirm-HostKey {
    if (Test-HostKeyKnown) { return $true }

    if (-not $script:KeyscanExe) {
        [System.Windows.Forms.MessageBox]::Show(
            "This PC has ssh but not ssh-keyscan, so the wizard cannot fetch the server's " +
            "identity to show you.`n`nOpen a Command Prompt and connect once by hand:`n`n" +
            "    ssh -i " + $S.KeyFile + " " + $S.SshUser + "@" + $S.SshHost + "`n`n" +
            "Answer 'yes' when it shows you the fingerprint, then come back here.",
            'OrbixERP - confirm the server by hand', 'OK', 'Warning') | Out-Null
        return $false
    }

    $scan = Invoke-CaptureText $script:KeyscanExe ('-p ' + $S.SshPort + ' -T 10 ' + $S.SshHost)
    $lines = @($scan.Out -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*#' })
    if ($lines.Count -eq 0) {
        [System.Windows.Forms.MessageBox]::Show(
            "No server answered at " + $S.SshHost + " on port " + $S.SshPort + ".`n`n" +
            "Check the address is right, the server is switched on, and that its firewall or " +
            "cloud security group allows SSH from this computer.",
            'OrbixERP - cannot reach the server', 'OK', 'Warning') | Out-Null
        return $false
    }

    # Fingerprints are computed by ssh-keygen from the scanned PUBLIC keys. Nothing secret
    # is in this file, so a temp file is fine.
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, (($lines -join "`n") + "`n"))
    $fp = Invoke-CaptureText $script:KeygenExe ('-l -f "' + $tmp + '"')
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue

    $shown = ($fp.Out -split "`r?`n" | Where-Object { $_.Trim() } | ForEach-Object { '    ' + $_.Trim() }) -join "`n"

    $answer = [System.Windows.Forms.MessageBox]::Show(
        "This is the first time this computer has connected to " + $S.SshHost + ".`n`n" +
        "The server identified itself with:`n`n" + $shown + "`n`n" +
        "Does that match your server?`n`n" +
        "How to check, on a cloud server: the fingerprints are printed in the instance's " +
        "system log the first time it boots (AWS: Actions - Monitor and troubleshoot - Get " +
        "system log). On a server you can already reach, run:`n`n" +
        "    ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`n`n" +
        "Answer Yes only if it matches. Answering Yes to a fingerprint you have not checked " +
        "means trusting whatever machine replied - which may not be yours.",
        'OrbixERP - confirm the server identity', 'YesNo', 'Warning')

    if ($answer -ne 'Yes') { return $false }

    $kh = Get-KnownHostsFile
    $dir = Split-Path -Parent $kh
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $existing = ''
    if (Test-Path $kh) { $existing = [System.IO.File]::ReadAllText($kh) }
    if ($existing -and -not $existing.EndsWith("`n")) { $existing += "`n" }
    [System.IO.File]::WriteAllText($kh, ($existing + ($lines -join "`n") + "`n"))
    Write-Log "Server identity confirmed and remembered in $kh"
    return $true
}

# ---------------------------------------------------------------------------
# The key file
#
# AWS hands out a .pem; that is the path this wizard is built around. Two things go wrong
# with it often enough to be worth catching by name.
# ---------------------------------------------------------------------------

# A key with a passphrase cannot be used here, and it is worth saying so BEFORE the attempt
# rather than letting it come back as "Permission denied (publickey)" - which reads as a
# wrong key or a wrong user name and sends the operator hunting in the wrong place.
#
# The wizard connects with BatchMode=yes, which forbids every prompt, and there is
# deliberately no way to supply a passphrase: OpenSSH takes one only from a terminal or from
# an agent, and the alternatives are a bundled helper binary or writing the passphrase where
# something can read it. Neither belongs in an installer.
function Test-KeyEncrypted {
    param([string]$Path)
    $text = ''
    try { $text = [System.IO.File]::ReadAllText($Path) } catch { return $false }

    # Classic PEM (what AWS still issues) announces it in a header.
    if ($text -match 'Proc-Type:\s*4,ENCRYPTED') { return $true }
    if ($text -notmatch 'BEGIN OPENSSH PRIVATE KEY') { return $false }

    # The newer OpenSSH format keeps it inside the base64: after the magic string comes a
    # length-prefixed cipher name, which is the word "none" on an unprotected key.
    $b64 = ($text -replace '-----[^-]*-----', '') -replace '\s', ''
    $bytes = $null
    try { $bytes = [Convert]::FromBase64String($b64) } catch { return $false }
    $magic = 'openssh-key-v1'
    if ($bytes.Length -lt ($magic.Length + 8)) { return $false }
    if ([System.Text.Encoding]::ASCII.GetString($bytes, 0, $magic.Length) -ne $magic) { return $false }
    $o = $magic.Length + 1
    $len = ([int]$bytes[$o] -shl 24) -bor ([int]$bytes[$o + 1] -shl 16) -bor ([int]$bytes[$o + 2] -shl 8) -bor [int]$bytes[$o + 3]
    if ($len -le 0 -or ($o + 4 + $len) -gt $bytes.Length) { return $false }
    return ([System.Text.Encoding]::ASCII.GetString($bytes, $o + 4, $len) -ne 'none')
}

function Test-KeyFile {
    param([string]$Path)
    if (-not $Path)             { return @{ Ok = $false; CanFix = $false; Reason = 'No key file chosen yet.' } }
    if (-not (Test-Path $Path)) { return @{ Ok = $false; CanFix = $false; Reason = 'That file does not exist.' } }

    $head = ''
    try { $head = (Get-Content $Path -TotalCount 1 -ErrorAction Stop) } catch { }

    # A PuTTY .ppk is not an OpenSSH key and never will be. Say so by name instead of
    # letting ssh report "invalid format".
    if ($head -match 'PuTTY-User-Key-File') {
        return @{ Ok = $false; CanFix = $false; Reason =
            'This is a PuTTY key (.ppk). Open it in PuTTYgen and choose Conversions - Export OpenSSH key, then use that file.' }
    }
    if ($head -notmatch 'BEGIN .*PRIVATE KEY') {
        return @{ Ok = $false; CanFix = $false; Reason =
            'This does not look like a private key. AWS gives you a file ending in .pem - choose that one, not the .pub file.' }
    }
    if (Test-KeyEncrypted $Path) {
        return @{ Ok = $false; CanFix = $false; Reason =
            ('This key is protected by a passphrase, and this wizard cannot ask you for one - ' +
             'Windows SSH only accepts a passphrase from a person typing at a terminal. Use a key ' +
             'without a passphrase for the installation, or remove the passphrase from a copy of ' +
             'this file with:  ssh-keygen -p -f <copy of the key>') }
    }

    # Windows OpenSSH refuses to use a key that other accounts can read, and says so in a
    # message most operators read as "the key is broken". A .pem copied out of a browser
    # download folder inherits exactly such permissions, so this is the normal case, not
    # an edge case.
    try {
        $me  = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $acl = Get-Acl $Path
        $allowed = @($me, 'NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')
        foreach ($rule in $acl.Access) {
            if ($allowed -notcontains $rule.IdentityReference.Value) {
                return @{ Ok = $false; CanFix = $true; Reason =
                    ("Other Windows accounts can read this key file (" + $rule.IdentityReference.Value +
                     "). OpenSSH refuses to use a key like that.") }
            }
        }
        if ($acl.Owner -ne $me -and $acl.Owner -ne 'BUILTIN\Administrators') {
            return @{ Ok = $false; CanFix = $true; Reason =
                ("This key file belongs to another Windows account (" + $acl.Owner + "). OpenSSH will refuse to use it.") }
        }
    } catch {
        # An unreadable ACL is not a reason to block; ssh will report it clearly enough.
    }

    return @{ Ok = $true; CanFix = $false; Reason = 'Key file looks usable.' }
}

# The documented fix, applied only after the operator agrees: stop inheriting permissions
# from the folder, and grant read to this account alone.
function Repair-KeyPermissions {
    param([string]$Path)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $r = Invoke-CaptureText 'icacls.exe' ('"' + $Path + '" /inheritance:r /grant:r "' + $me + ':(R)"')
    if ($r.Code -ne 0) { return $false }
    # Taking ownership can fail when the file belongs to somebody else and this account is
    # not an administrator; the grant above is the part that usually matters, so a failure
    # here is not treated as fatal - Test-KeyFile runs again and has the last word.
    [void](Invoke-CaptureText 'icacls.exe' ('"' + $Path + '" /setowner "' + $me + '"'))
    return $true
}

# ---------------------------------------------------------------------------
# Settings
#
# The same value map as setup-wizard.ps1's Set-EnvFile, with the additions a server needs
# (the public address and the HTTPS overlay). Built here as text and sent straight into
# the server's .env over stdin - it is never written on this PC, because it contains both
# the database password and the first administrator password.
# ---------------------------------------------------------------------------
function Get-EnvContent {
    $tpl = Join-Path $SourceDir '.env.example'
    if (-not (Test-Path $tpl)) { throw '.env.example is missing from the bundle.' }

    $tlsOn = ($S.Tls -ne 'off')
    $values = @{
        ERP_DB_MODE                    = 'docker'
        ERP_DB_NAME                    = $S.DbName
        ERP_DB_USER                    = $S.DbUser
        ERP_DB_PASSWORD                = $S.DbPassword
        ERP_HTTP_PORT                  = $S.HttpPort
        ERP_BIND_ADDR                  = $S.BindAddr
        ERP_STACK_NAME                 = $S.StackName
        ERP_TLS_ENABLED                = $(if ($tlsOn) { 'true' } else { 'false' })
        ERP_PUBLIC_HOST                = $S.PublicHost
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
    return (($out -join "`n") + "`n")
}

# ---------------------------------------------------------------------------
# Remote scripts
#
# Each of these is a plain shell script sent over stdin. They are gathered here rather
# than inlined at the call sites so that what runs on somebody else's server can be read
# in one place.
# ---------------------------------------------------------------------------

# One connection answers every preflight question. Printed as KEY=VALUE so the parsing
# cannot be confused by a locale or by a stray warning from a login script.
function Get-PreflightScript {
    $dir = $S.RemoteDir
    return @"
LC_ALL=C
export LC_ALL
echo "KERNEL=`$(uname -s)"
echo "ARCH=`$(uname -m)"
ID=unknown; ID_LIKE=; PRETTY_NAME=
if [ -r /etc/os-release ]; then . /etc/os-release; fi
echo "OSID=`${ID}"
echo "OSLIKE=`${ID_LIKE}"
echo "OSNAME=`${PRETTY_NAME}"
echo "WHOAMI=`$(id -un)"
if command -v docker >/dev/null 2>&1; then echo "DOCKER=yes"; else echo "DOCKER=no"; fi
if docker info >/dev/null 2>&1; then echo "DOCKER_USABLE=yes"; else echo "DOCKER_USABLE=no"; fi
if docker compose version >/dev/null 2>&1; then echo "COMPOSE=yes"; else echo "COMPOSE=no"; fi
if sudo -n true >/dev/null 2>&1; then
  echo "SUDO=nopass"
elif command -v sudo >/dev/null 2>&1; then
  echo "SUDO=password"
else
  echo "SUDO=none"
fi
d='$dir'
while [ ! -d "`$d" ] && [ "`$d" != "/" ]; do d=`$(dirname "`$d"); done
echo "DFDIR=`$d"
echo "FREEKB=`$(df -Pk "`$d" 2>/dev/null | awk 'NR==2{print `$4}')"
if [ -w "`$d" ]; then echo "WRITABLE=yes"; else echo "WRITABLE=no"; fi
if [ -f '$dir/.env' ]; then
  echo "INSTALLED=yes"
  echo "INSTALLED_VERSION=`$(grep -E '^[[:space:]]*ERP_VERSION=' '$dir/.env' | tail -1 | cut -d= -f2- | tr -d '\r')"
  echo "INSTALLED_PORT=`$(grep -E '^[[:space:]]*ERP_HTTP_PORT=' '$dir/.env' | tail -1 | cut -d= -f2- | tr -d '\r')"
  # Read back so the Manage page offers the address people actually use. Without these the
  # "Open in browser" button on an HTTPS installation would hand out an http:// address on a
  # port that is deliberately bound to the server's own loopback and answers nobody.
  echo "INSTALLED_TLS=`$(grep -E '^[[:space:]]*ERP_TLS_ENABLED=' '$dir/.env' | tail -1 | cut -d= -f2- | tr -d '\r')"
  echo "INSTALLED_PUBLIC=`$(grep -E '^[[:space:]]*ERP_PUBLIC_HOST=' '$dir/.env' | tail -1 | cut -d= -f2- | tr -d '\r')"
else
  echo "INSTALLED=no"
fi
exit 0
"@
}

# Creating the installation directory. Tried as the login user first - on a server where
# the operator owns /opt, or where the directory is under their home, no administrator
# rights are needed at all and none are asked for.
function Get-MakeDirScript {
    $dir = $S.RemoteDir
    return @"
set -e
if mkdir -p '$dir' 2>/dev/null && [ -w '$dir' ]; then
  echo "MADE=user"
  exit 0
fi
exit 7
"@
}

function Get-MakeDirSudoScript {
    $dir = $S.RemoteDir
    $user = $S.SshUser
    return @"
set -e
mkdir -p '$dir'
chown '$user' '$dir'
chmod 755 '$dir'
echo "MADE=sudo"
"@
}

# Installing Docker. Runs under sudo. Distro families are detected from /etc/os-release
# rather than guessed from the hostname or the SSH banner.
#
# Amazon Linux is checked BEFORE the Red Hat family on purpose: Amazon Linux 2023 declares
# ID_LIKE="fedora", so a naive test sends it down the Red Hat path, where the convenience
# script from get.docker.com refuses to run.
#
# Exit codes are meaningful, because the messages the operator sees depend on them:
#   0 installed   3 distro not supported   4 install failed (usually no internet)
#   5 installed but has no 'docker compose'
function Get-DockerInstallScript {
    $user = $S.SshUser
    return @"
set -e
ID=unknown; ID_LIKE=
if [ -r /etc/os-release ]; then . /etc/os-release; fi
family=""
case " `$ID `$ID_LIKE " in
  *" amzn "*)                              family=amazon ;;
  *" debian "*|*" ubuntu "*)               family=debian ;;
  *" rhel "*|*" centos "*|*" fedora "*)    family=rhel ;;
esac
echo "FAMILY=`$family"
[ -n "`$family" ] || exit 3

if [ "`$family" = "amazon" ]; then
  (dnf -y install docker || yum -y install docker) || exit 4
else
  # The convenience script from Docker adds Docker's own repository, which is where the
  # 'docker compose' plugin comes from. It needs the server to reach the internet once.
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh || exit 4
  elif command -v wget >/dev/null 2>&1; then
    wget -qO /tmp/get-docker.sh https://get.docker.com || exit 4
  else
    exit 4
  fi
  sh /tmp/get-docker.sh || exit 4
  rm -f /tmp/get-docker.sh
fi

systemctl enable --now docker >/dev/null 2>&1 || service docker start >/dev/null 2>&1 || true

# The operator's own account must be able to use Docker without sudo, because install.sh
# and orbixerp.sh run as that account. The new group membership applies to the NEXT login,
# which is exactly what the wizard's next connection is.
usermod -aG docker '$user' || true

docker compose version >/dev/null 2>&1 || {
  (dnf -y install docker-compose-plugin || yum -y install docker-compose-plugin || apt-get install -y docker-compose-plugin) >/dev/null 2>&1 || true
}
docker compose version >/dev/null 2>&1 || exit 5
echo "DOCKER_OK=yes"
"@
}

# Compares what arrived against what was sent. An upload that is truncated by a dropped
# connection produces an image file that fails to load much later, with a message about a
# damaged archive and no hint that the network was at fault.
function Get-ChecksumScript {
    param([string]$RemoteFile)
    return @"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum '$RemoteFile' | cut -d' ' -f1
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 '$RemoteFile' | cut -d' ' -f1
else
  echo SKIP
fi
"@
}

# The Let's Encrypt option is nothing more than the one-line edit the Caddyfile itself
# documents: remove `tls internal` and Caddy asks a public certificate authority for a
# real certificate instead of minting its own.
function Get-LetsEncryptScript {
    $dir = $S.RemoteDir
    return @"
set -e
f='$dir/Caddyfile'
[ -f "`$f" ] || exit 8
sed -i '/^[[:space:]]*tls internal[[:space:]]*`$/d' "`$f"
# Written as an if rather than 'grep ... && exit', because under set -e a failing grep at
# the end of an AND list makes the whole script exit non-zero - reporting a failure for
# the case that actually succeeded.
if grep -q 'tls internal' "`$f"; then exit 9; fi
echo "CADDY=letsencrypt"
"@
}

function Get-RunScript {
    param([string]$Command)
    $dir = $S.RemoteDir
    # `bash <script>` rather than `./<script>`, so a file that lost its executable bit in
    # transit still runs.
    return "cd '$dir' && bash $Command"
}

# ---------------------------------------------------------------------------
# -DryRun
#
# Prints every command line the wizard can build, for a representative server, and exits.
# Nothing connects, nothing is written. This is how the command construction is verified
# on a machine that has no server to talk to.
# ---------------------------------------------------------------------------
function Show-DryRunCommand {
    param([string]$Title, [string]$File, [string]$Arguments, [string]$Stdin = '')
    Write-Host ''
    Write-Host ("  " + $Title)
    Write-Host ("    " + $File + ' ' + $Arguments)
    if ($Stdin) { Write-Host ("    stdin: " + $Stdin) }
}

function Invoke-DryRun {
    $S.SshHost   = 'erp.example.com'
    $S.SshPort   = '22'
    $S.SshUser   = 'ubuntu'
    $S.KeyFile   = 'C:\Users\Operator\Downloads\orbix-server.pem'
    $S.RemoteDir = '/opt/orbixerp'
    $S.PublicHost= 'erp.example.com'
    $S.Version   = (Get-Meta 'ERP_VERSION'); if (-not $S.Version) { $S.Version = '1.0.0' }
    $S.BundleArch= (Get-Meta 'BUNDLE_ARCH'); if (-not $S.BundleArch) { $S.BundleArch = 'amd64' }
    $S.DbPassword = 'EXAMPLE-DB-PASSWORD-NEVER-REAL'
    $S.AdminPass  = 'EXAMPLE-ADMIN-PASSWORD'
    $S.SudoMode   = 'password'
    # The HTTPS example, because it is the one with a consequence: choosing either encrypted
    # option also moves the plain port onto the server's own loopback, so the unencrypted
    # door is not left standing open beside the encrypted one. Save-Page does this for real;
    # it is repeated here so the .env printed below is the .env that would be sent.
    $S.Tls        = 'letsencrypt'
    $S.HttpPort   = '8080'
    $S.BindAddr   = '127.0.0.1'

    Write-Host ''
    Write-Host '  OrbixERP - remote setup wizard, dry run'
    Write-Host '  ======================================='
    Write-Host ''
    Write-Host '  No connection is made and nothing is changed. These are the exact command'
    Write-Host '  lines the wizard would run against the example server below.'
    Write-Host ''
    Write-Host ("  server        " + $S.SshUser + '@' + $S.SshHost + ':' + $S.SshPort)
    Write-Host ("  key file      " + $S.KeyFile)
    Write-Host ("  install into  " + $S.RemoteDir)
    Write-Host ("  bundle        " + $S.Version + ' (' + $S.BundleArch + ')')
    Write-Host ''
    Write-Host '  Tools resolved on this PC'
    Write-Host ("    ssh          " + $(if ($script:SshExe)     { $script:SshExe }     else { 'NOT FOUND' }))
    Write-Host ("    scp          " + $(if ($script:ScpExe)     { $script:ScpExe }     else { 'NOT FOUND' }))
    Write-Host ("    ssh-keygen   " + $(if ($script:KeygenExe)  { $script:KeygenExe }  else { 'NOT FOUND' }))
    Write-Host ("    ssh-keyscan  " + $(if ($script:KeyscanExe) { $script:KeyscanExe } else { 'NOT FOUND' }))
    Write-Host ("    known_hosts  " + (Get-KnownHostsFile))
    Write-Host ''
    Write-Host '  --- 1. host key confirmation (before any connection is attempted) ---'
    Show-DryRunCommand 'is this server already trusted?' $script:KeygenExe `
        ('-F "' + (Get-KnownHostsTarget) + '" -f "' + (Get-KnownHostsFile) + '"')
    Show-DryRunCommand 'fetch the keys it offers (no connection is authenticated)' $script:KeyscanExe `
        ('-p ' + $S.SshPort + ' -T 10 ' + $S.SshHost)
    Show-DryRunCommand 'show their fingerprints to the operator' $script:KeygenExe '-l -f "<temp file of scanned public keys>"'
    Write-Host ''
    Write-Host '    then: the fingerprints are shown in a dialog and the operator answers Yes or No.'
    Write-Host '    Only on Yes are those lines appended to known_hosts. StrictHostKeyChecking is'
    Write-Host '    never disabled, and no key is added without that answer.'
    Write-Host '    If known_hosts already holds a DIFFERENT key for this address, ssh refuses the'
    Write-Host '    connection and the wizard stops with a warning that the identity has changed -'
    Write-Host '    it does not offer to overwrite it.'

    Write-Host ''
    Write-Host '  --- 2. the key file on this PC ---'
    Show-DryRunCommand 'lock the key down if other accounts can read it (only after the operator agrees)' 'icacls.exe' `
        ('"' + $S.KeyFile + '" /inheritance:r /grant:r "<this Windows account>:(R)"')

    Write-Host ''
    Write-Host '  --- 3. preflight ---'
    Show-DryRunCommand 'ask the server about itself' $script:SshExe (Get-SshArgs 'bash -s') `
        ((Get-PreflightScript).Length.ToString() + ' bytes of shell script (no secrets)')

    Write-Host ''
    Write-Host '  --- 4. preparing the server ---'
    Show-DryRunCommand 'check the administrator password before it is relied on' $script:SshExe (Get-SshArgs "sudo -S -p '' bash -s") `
        'the sudo password (one line, from memory), then: exit 0'
    Show-DryRunCommand 'create the folder as the login user' $script:SshExe (Get-SshArgs 'bash -s') `
        ((Get-MakeDirScript).Length.ToString() + ' bytes of shell script (no secrets)')
    Show-DryRunCommand 'or, if that is refused, with administrator rights' $script:SshExe (Get-SshArgs "sudo -S -p '' bash -s") `
        ('the sudo password (one line, from memory), then ' + (Get-MakeDirSudoScript).Length.ToString() + ' bytes of shell script')
    Show-DryRunCommand 'install Docker when it is missing' $script:SshExe (Get-SshArgs "sudo -S -p '' bash -s") `
        ('the sudo password (one line, from memory), then ' + (Get-DockerInstallScript).Length.ToString() + ' bytes of shell script')
    Write-Host ''
    Write-Host '    With passwordless sudo the remote command is instead:  sudo -n bash -s'
    Write-Host '    In neither case does the password appear in the command line, so it cannot be'
    Write-Host '    seen in the server process list.'

    Write-Host ''
    Write-Host '  --- 5. uploading the bundle ---'
    $sample = @((Join-Path $SourceDir 'install.sh'), (Join-Path $SourceDir 'orbixerp.sh'), (Join-Path $SourceDir 'docs'))
    Show-DryRunCommand 'the operating files, in one connection' $script:ScpExe (Get-ScpArgs $sample $S.RemoteDir)
    Show-DryRunCommand 'each application image, streamed so progress can be shown' $script:SshExe `
        (Get-SshArgs ('"cat > ' + "'" + $S.RemoteDir + "/images/orbixerp-api-" + $S.Version + '-' + $S.BundleArch + '.tar.gz' + "'" + '"')) `
        'the file bytes, 256 KB at a time, with a live progress bar'
    Show-DryRunCommand 'check what arrived is what was sent' $script:SshExe (Get-SshArgs 'bash -s') `
        ('sha256sum of the uploaded file')

    Write-Host ''
    Write-Host '  --- 6. settings (this is the one that carries secrets) ---'
    Show-DryRunCommand 'write .env on the server' $script:SshExe `
        (Get-SshArgs ('"umask 077; cat > ' + "'" + $S.RemoteDir + "/.env'" + '"')) `
        'the .env text, from memory, over the pipe'
    Write-Host ''
    Write-Host '    The .env this would send, with the two passwords masked:'
    Write-Host ''
    $env = Get-EnvContent
    foreach ($line in ($env -split "`n")) {
        if ($line -match '^\s*#' -or -not $line.Trim()) { continue }
        $safe = $line
        if ($line -match '^(ERP_DB_PASSWORD|ERP_BOOTSTRAP_ADMIN_PASSWORD)=') {
            $safe = ($line -split '=', 2)[0] + '=<generated, 20-32 characters, never logged>'
        }
        Write-Host ('      ' + $safe)
    }

    Write-Host ''
    Write-Host '  --- 7. installing, by driving the bundle scripts already on the server ---'
    Show-DryRunCommand 'install (loads images, makes keys, starts, waits until healthy)' $script:SshExe (Get-SshArgs 'bash -s') `
        ((Get-RunScript './install.sh --defaults').Length.ToString() + ' bytes: ' + (Get-RunScript './install.sh --defaults'))
    Show-DryRunCommand 'switch Caddy to a real certificate (only if Lets Encrypt was chosen)' $script:SshExe (Get-SshArgs 'bash -s') `
        ((Get-LetsEncryptScript).Length.ToString() + ' bytes of shell script')

    Write-Host ''
    Write-Host '  --- 8. day-to-day management ---'
    foreach ($c in @('./orbixerp.sh status', './orbixerp.sh logs', './orbixerp.sh backup',
                     './orbixerp.sh restart', './orbixerp.sh stop', './orbixerp.sh version')) {
        Show-DryRunCommand $c $script:SshExe (Get-SshArgs 'bash -s') ((Get-RunScript $c))
    }

    Write-Host ''
    Write-Host '  --- 9. updating an existing installation ---'
    $stage = $S.RemoteDir + '/.update-' + $S.Version
    Write-Host ("    the new bundle is uploaded to " + $stage + ", then:")
    Show-DryRunCommand 'update (takes a database backup first and stops if that fails)' $script:SshExe (Get-SshArgs 'bash -s') `
        ((Get-RunScript ("./orbixerp.sh update '" + $stage + "'")))
    Show-DryRunCommand 'remove the staging copy afterwards' $script:SshExe (Get-SshArgs 'bash -s') `
        ("rm -rf '" + $stage + "'")

    Write-Host ''
    Write-Host '  Every one of the above has its exit status checked. 255 is reported as a lost'
    Write-Host '  connection; anything else non-zero stops the wizard with the step that failed.'
    Write-Host ''
}

if ($DryRun) {
    Invoke-DryRun
    exit 0
}

# ---------------------------------------------------------------------------
# Form scaffolding - deliberately identical to setup-wizard.ps1
# ---------------------------------------------------------------------------
$form                 = New-Object System.Windows.Forms.Form
$form.Text            = 'OrbixERP - Remote Setup'
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

# Asks for a password without it ever being echoed or stored anywhere but memory.
function Read-SecretDialog {
    param([string]$Title, [string]$Prompt)
    $d = New-Object System.Windows.Forms.Form
    $d.Text = $Title
    $d.Size = New-Object System.Drawing.Size(460, 200)
    $d.StartPosition = 'CenterParent'
    $d.FormBorderStyle = 'FixedDialog'
    $d.MaximizeBox = $false; $d.MinimizeBox = $false
    $d.BackColor = [System.Drawing.Color]::White
    New-Label $d $Prompt 18 18 410 60 -Dim | Out-Null
    $t = New-TextBox $d '' 18 84 410
    $t.UseSystemPasswordChar = $true
    $okB = New-Object System.Windows.Forms.Button
    $okB.Text = 'OK'; $okB.Size = New-Object System.Drawing.Size(96, 28)
    $okB.Location = New-Object System.Drawing.Point(232, 120); $okB.FlatStyle = 'System'
    $okB.DialogResult = 'OK'
    $d.Controls.Add($okB)
    $caB = New-Object System.Windows.Forms.Button
    $caB.Text = 'Cancel'; $caB.Size = New-Object System.Drawing.Size(96, 28)
    $caB.Location = New-Object System.Drawing.Point(332, 120); $caB.FlatStyle = 'System'
    $caB.DialogResult = 'Cancel'
    $d.Controls.Add($caB)
    $d.AcceptButton = $okB; $d.CancelButton = $caB
    $r = $d.ShowDialog()
    $sec = $null
    if ($r -eq 'OK' -and $t.Text) {
        $sec = New-Object System.Security.SecureString
        foreach ($ch in $t.Text.ToCharArray()) { $sec.AppendChar($ch) }
        $sec.MakeReadOnly()
    }
    # Blank the box before disposing it, so the characters do not linger in a live control.
    $t.Text = ''
    $d.Dispose()
    return $sec
}

# ---------------------------------------------------------------------------
# Page 0 - welcome and checks on THIS computer
# ---------------------------------------------------------------------------
$pgWelcome = New-Page
New-Label $pgWelcome 'This wizard installs OrbixERP on a server, over the network.' 24 22 620 20 -Bold | Out-Null
New-Label $pgWelcome 'Everything is sent from this computer: the server needs no internet connection. Afterwards, use this same wizard to check the system, back it up, update it or restart it - you never need to type commands on the server.' 24 46 620 52 -Dim | Out-Null
New-Label $pgWelcome 'Checks on this computer' 24 108 620 20 -Bold | Out-Null

$lstLocal = New-Object System.Windows.Forms.ListView
$lstLocal.Location = New-Object System.Drawing.Point(24, 132)
$lstLocal.Size = New-Object System.Drawing.Size(620, 150)
$lstLocal.View = 'Details'; $lstLocal.FullRowSelect = $true; $lstLocal.HeaderStyle = 'Nonclickable'
$lstLocal.Columns.Add('', 34) | Out-Null
$lstLocal.Columns.Add('Check', 210) | Out-Null
$lstLocal.Columns.Add('Result', 350) | Out-Null
$pgWelcome.Controls.Add($lstLocal)

$lblLocalHint = New-Label $pgWelcome '' 24 290 620 56 -Dim
$btnRecheckLocal = New-Object System.Windows.Forms.Button
$btnRecheckLocal.Text = 'Check again'; $btnRecheckLocal.Size = New-Object System.Drawing.Size(110, 28)
$btnRecheckLocal.Location = New-Object System.Drawing.Point(24, 352); $btnRecheckLocal.FlatStyle = 'System'
$pgWelcome.Controls.Add($btnRecheckLocal)

$script:LocalPassed = $false

function Add-Check {
    param($List, [bool]$Pass, [string]$Name, [string]$Detail)
    $i = New-Object System.Windows.Forms.ListViewItem(@($(if ($Pass) { 'OK' } else { 'X' }), $Name, $Detail))
    $i.ForeColor = if ($Pass) { [System.Drawing.Color]::FromArgb(20, 110, 50) } else { [System.Drawing.Color]::FromArgb(170, 30, 30) }
    $List.Items.Add($i) | Out-Null
    return $Pass
}

function Invoke-LocalChecks {
    $lstLocal.Items.Clear()
    $ok = $true

    $S.Version   = Get-Meta 'ERP_VERSION'
    $S.BundleArch = Get-Meta 'BUNDLE_ARCH'

    if (-not $script:SshExe -or -not $script:ScpExe) {
        $ok = (Add-Check $lstLocal $false 'Windows SSH client' 'Not enabled - see the note below') -and $ok
    } else {
        [void](Add-Check $lstLocal $true 'Windows SSH client' 'ready')
    }

    $imgDir = Join-Path $SourceDir 'images'
    $imgs = @()
    if (Test-Path $imgDir) { $imgs = @(Get-ChildItem $imgDir -File | Where-Object { $_.Name -like '*.tar*' }) }
    if ($imgs.Count -eq 0) {
        $ok = (Add-Check $lstLocal $false 'Installation files' 'The images folder is missing or empty - unpack the bundle again') -and $ok
    } else {
        $bytes = ($imgs | Measure-Object -Property Length -Sum).Sum
        [void](Add-Check $lstLocal $true 'Installation files' "$($imgs.Count) application images, $(Format-Size $bytes) to upload")
    }

    if ($S.Version) {
        [void](Add-Check $lstLocal $true 'This bundle' "version $($S.Version) for $($S.BundleArch) servers")
    } else {
        $ok = (Add-Check $lstLocal $false 'This bundle' 'The VERSION file is missing - unpack the bundle again') -and $ok
    }

    $script:LocalPassed = $ok
    if ($ok) {
        $lblLocalHint.Text = 'Everything needed is here. Click Next to describe your server.'
    } elseif (-not $script:SshExe) {
        $lblLocalHint.Text = 'Turn the SSH client on: Settings - System - Optional features - Add an optional feature - OpenSSH Client. Then click Check again. Nothing else needs installing.'
    } else {
        $lblLocalHint.Text = 'Fix the items marked X above, then click Check again. Nothing has been changed.'
    }
    $btnNext.Enabled = $ok
}
$btnRecheckLocal.Add_Click({ Invoke-LocalChecks })

# ---------------------------------------------------------------------------
# Page 1 - the server
# ---------------------------------------------------------------------------
$pgServer = New-Page
New-Label $pgServer 'Which server, and how do we sign in to it?' 24 22 620 20 -Bold | Out-Null
New-Label $pgServer 'These are the same details you would use to connect to the server yourself. Nothing is changed on it yet.' 24 46 620 20 -Dim | Out-Null

New-Label $pgServer 'Server address' 24 88 150 20 | Out-Null
$txtHost = New-TextBox $pgServer '' 180 85 320
New-Label $pgServer 'Name or IP address, e.g. 16.170.11.41' 508 88 200 20 -Dim | Out-Null

New-Label $pgServer 'Port' 24 120 150 20 | Out-Null
$txtSshPort = New-TextBox $pgServer $S.SshPort 180 117 80
New-Label $pgServer 'Usually 22' 270 120 200 20 -Dim | Out-Null

New-Label $pgServer 'Sign in as' 24 152 150 20 | Out-Null
$txtUser = New-TextBox $pgServer $S.SshUser 180 149 200
New-Label $pgServer 'ubuntu, ec2-user, admin, debian...' 390 152 250 20 -Dim | Out-Null

New-Label $pgServer 'Key file' 24 184 150 20 | Out-Null
$txtKey = New-TextBox $pgServer '' 180 181 350
$btnKey = New-Object System.Windows.Forms.Button
$btnKey.Text = 'Browse...'; $btnKey.Size = New-Object System.Drawing.Size(90, 24)
$btnKey.Location = New-Object System.Drawing.Point(538, 180); $btnKey.FlatStyle = 'System'
$pgServer.Controls.Add($btnKey)
$btnKey.Add_Click({
    $d = New-Object System.Windows.Forms.OpenFileDialog
    $d.Title = 'Choose the private key file for this server'
    $d.Filter = 'Key files (*.pem;*.key)|*.pem;*.key|All files (*.*)|*.*'
    if ($d.ShowDialog() -eq 'OK') { $txtKey.Text = $d.FileName }
})
$lblKeyNote = New-Label $pgServer 'The .pem file your hosting provider gave you when the server was created. Passwords cannot be used: Windows SSH has no way to supply one without a person typing it.' 180 208 460 44 -Dim

New-Label $pgServer 'Install into' 24 264 150 20 | Out-Null
$txtRemoteDir = New-TextBox $pgServer $S.RemoteDir 180 261 350
New-Label $pgServer 'A folder of its own on the server. Everything - settings, security keys and backups - stays together there, and no root rights are needed to run or back it up afterwards.' 180 288 460 44 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Page 2 - checks on the server
# ---------------------------------------------------------------------------
$pgCheck = New-Page
New-Label $pgCheck 'Checking the server' 24 22 620 20 -Bold | Out-Null
New-Label $pgCheck 'Nothing is changed on the server by these checks - they only ask it questions.' 24 46 620 20 -Dim | Out-Null

$lstRemote = New-Object System.Windows.Forms.ListView
$lstRemote.Location = New-Object System.Drawing.Point(24, 76)
$lstRemote.Size = New-Object System.Drawing.Size(620, 170)
$lstRemote.View = 'Details'; $lstRemote.FullRowSelect = $true; $lstRemote.HeaderStyle = 'Nonclickable'
$lstRemote.Columns.Add('', 34) | Out-Null
$lstRemote.Columns.Add('Check', 190) | Out-Null
$lstRemote.Columns.Add('Result', 370) | Out-Null
$pgCheck.Controls.Add($lstRemote)

$lblRemoteHint = New-Label $pgCheck '' 24 252 620 56 -Dim

$grpFound = New-Object System.Windows.Forms.GroupBox
$grpFound.Text = 'OrbixERP is already installed on this server'
$grpFound.Location = New-Object System.Drawing.Point(24, 306); $grpFound.Size = New-Object System.Drawing.Size(620, 88)
$grpFound.Visible = $false
$pgCheck.Controls.Add($grpFound)
$rbManage = New-Object System.Windows.Forms.RadioButton
$rbManage.Text = 'Manage it - check it, back it up, restart it, view recent activity'
$rbManage.Location = New-Object System.Drawing.Point(16, 24); $rbManage.Size = New-Object System.Drawing.Size(580, 22)
$rbManage.Checked = $true; $rbManage.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$grpFound.Controls.Add($rbManage)
$rbUpdate = New-Object System.Windows.Forms.RadioButton
$rbUpdate.Text = 'Update it to this bundle'
$rbUpdate.Location = New-Object System.Drawing.Point(16, 50); $rbUpdate.Size = New-Object System.Drawing.Size(580, 22)
$rbUpdate.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$grpFound.Controls.Add($rbUpdate)

$btnRecheck = New-Object System.Windows.Forms.Button
$btnRecheck.Text = 'Check again'; $btnRecheck.Size = New-Object System.Drawing.Size(110, 28)
$btnRecheck.Location = New-Object System.Drawing.Point(534, 400); $btnRecheck.FlatStyle = 'System'
$pgCheck.Controls.Add($btnRecheck)

$script:RemotePassed = $false

# The disk numbers come from what this bundle actually weighs: the tarballs have to land
# on the server, the loaded images take roughly 1.5 GB, and the database grows from there.
function Get-UploadBytes {
    $imgDir = Join-Path $SourceDir 'images'
    if (-not (Test-Path $imgDir)) { return 0 }
    $sum = (Get-ChildItem $imgDir -File | Where-Object { $_.Name -like '*.tar*' } | Measure-Object -Property Length -Sum).Sum
    if (-not $sum) { return 0 }
    return [long]$sum
}

function Invoke-RemoteChecks {
    $lstRemote.Items.Clear()
    $grpFound.Visible = $false
    $script:RemotePassed = $false
    $btnNext.Enabled = $false
    $lblRemoteHint.Text = 'Connecting...'
    [System.Windows.Forms.Application]::DoEvents()

    # 1. the key file, before anything is attempted with it
    $k = Test-KeyFile $S.KeyFile
    if (-not $k.Ok -and $k.CanFix) {
        $r = [System.Windows.Forms.MessageBox]::Show(
            $k.Reason + "`n`nWindows can put that right: the file's permissions are reset so that only " +
            "your own account may read it. Nothing else about the file changes.`n`nFix it now?",
            'OrbixERP - the key file is readable by others', 'YesNo', 'Warning')
        if ($r -eq 'Yes') {
            [void](Repair-KeyPermissions $S.KeyFile)
            $k = Test-KeyFile $S.KeyFile
        }
    }
    if (-not $k.Ok) {
        [void](Add-Check $lstRemote $false 'Key file' $k.Reason)
        $lblRemoteHint.Text = 'Go Back and choose a different key file, then check again.'
        return
    }
    [void](Add-Check $lstRemote $true 'Key file' 'readable by this account only')

    # 2. the server identity, before we authenticate to it
    if (-not (Confirm-HostKey)) {
        [void](Add-Check $lstRemote $false 'Server identity' 'not confirmed - nothing was sent to the server')
        $lblRemoteHint.Text = 'The server identity has to be confirmed before anything is sent to it. Click Check again when you have the fingerprint to compare.'
        return
    }
    [void](Add-Check $lstRemote $true 'Server identity' 'confirmed and remembered')

    # 3. can we sign in?
    #
    # -AllowFailure because this is the step that fails on an ordinary first attempt - wrong
    # user name, wrong key, closed security group - and every one of those has to come back
    # as a red row the operator can correct by clicking Back, not as the wizard falling over.
    $r = Invoke-Remote 'echo CONNECTED' -Quiet -AllowFailure
    if ($r.Code -ne 0 -or $r.Out -notmatch 'CONNECTED') {
        $why = $r.Err.Trim()
        if ($why -match 'REMOTE HOST IDENTIFICATION HAS CHANGED|Host key verification failed') {
            # The one failure here that may not be a mistake. known_hosts already holds a key
            # for this address and the server offered a different one, so Confirm-HostKey saw
            # it as trusted and said nothing.
            [void](Add-Check $lstRemote $false 'Server identity' 'CHANGED since this computer last connected - nothing was sent')
            [System.Windows.Forms.MessageBox]::Show((Get-ConnectionProblem $r),
                'OrbixERP - the server identity has changed', 'OK', 'Warning') | Out-Null
            $lblRemoteHint.Text = 'The identity of that server has changed. Nothing has been sent to it. Do not continue until you know why.'
            return
        }
        $hint = 'Could not sign in'
        if ($why -match 'Permission denied') {
            $hint = "The server refused this key for user '$($S.SshUser)' - check the user name and the key file"
        } elseif ($why -match 'Connection refused|timed out|No route') {
            $hint = 'No answer on that address and port - check the address and the firewall or security group'
        }
        [void](Add-Check $lstRemote $false 'Signing in' $hint)
        $lblRemoteHint.Text = "The server said: " + ($why -replace "`r?`n", ' ')
        return
    }
    [void](Add-Check $lstRemote $true 'Signing in' "signed in as $($S.SshUser)")

    # 4. everything else, in a single connection
    $pf = Invoke-Remote (Get-PreflightScript) -Quiet -AllowFailure
    if ($pf.Code -ne 0) {
        [void](Add-Check $lstRemote $false 'Reading the server' 'The server did not answer the checks')
        $lblRemoteHint.Text = $pf.Err.Trim()
        return
    }
    $f = @{}
    foreach ($line in ($pf.Out -split "`r?`n")) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $f[$matches[1]] = $matches[2].Trim() }
    }

    $ok = $true

    if ($f['KERNEL'] -ne 'Linux') {
        $ok = (Add-Check $lstRemote $false 'Operating system' "This wizard installs onto Linux servers; this one reports '$($f['KERNEL'])'") -and $ok
    } else {
        $osName = $f['OSNAME']; if (-not $osName) { $osName = $f['OSID'] }
        [void](Add-Check $lstRemote $true 'Operating system' $osName)
    }
    $S.ServerOs = $f['OSID']

    # Architecture. Caught here deliberately: Docker's own failure for a wrong-architecture
    # image is "exec format error", which tells a client nothing actionable - and by then
    # 350 MB has been uploaded.
    $arch = switch ($f['ARCH']) { 'x86_64' { 'amd64' } 'amd64' { 'amd64' } 'aarch64' { 'arm64' } 'arm64' { 'arm64' } default { $f['ARCH'] } }
    $S.ServerArch = $arch
    if ($S.BundleArch -and $arch -ne $S.BundleArch) {
        $ok = (Add-Check $lstRemote $false 'Processor type' "This bundle is for $($S.BundleArch) servers; this one is $arch - ask your supplier for the $arch bundle") -and $ok
    } else {
        [void](Add-Check $lstRemote $true 'Processor type' $arch)
    }

    # Disk
    $needBytes = (Get-UploadBytes) + 2GB
    $freeBytes = 0
    if ($f['FREEKB']) { $freeBytes = [double]$f['FREEKB'] * 1024 }
    $S.FreeKb = $f['FREEKB']
    if ($freeBytes -lt $needBytes) {
        $ok = (Add-Check $lstRemote $false 'Free disk space' ("Only $(Format-Size $freeBytes) free on $($f['DFDIR']) - about $(Format-Size $needBytes) is needed")) -and $ok
    } elseif ($freeBytes -lt ($needBytes + 5GB)) {
        [void](Add-Check $lstRemote $true 'Free disk space' "$(Format-Size $freeBytes) free - enough to install, but add more before the database grows")
    } else {
        [void](Add-Check $lstRemote $true 'Free disk space' "$(Format-Size $freeBytes) free on $($f['DFDIR'])")
    }

    # Sudo
    $S.SudoMode = $f['SUDO']

    # Docker
    $S.HasDocker  = ($f['DOCKER_USABLE'] -eq 'yes')
    $S.HasCompose = ($f['COMPOSE'] -eq 'yes')
    if ($S.HasDocker -and $S.HasCompose) {
        [void](Add-Check $lstRemote $true 'Docker' 'installed and usable by this account')
    } elseif ($S.SudoMode -eq 'none') {
        $ok = (Add-Check $lstRemote $false 'Docker' "Not usable here, and '$($S.SshUser)' cannot use sudo to install it") -and $ok
    } else {
        [void](Add-Check $lstRemote $true 'Docker' 'not installed yet - this wizard will install it')
    }

    # Somewhere to put it
    if ($f['WRITABLE'] -ne 'yes' -and $S.SudoMode -eq 'none') {
        $ok = (Add-Check $lstRemote $false 'Install folder' "Cannot create $($S.RemoteDir), and this account cannot use sudo") -and $ok
    } else {
        [void](Add-Check $lstRemote $true 'Install folder' $S.RemoteDir)
    }

    # Already there?
    $S.InstalledVersion = ''
    if ($f['INSTALLED'] -eq 'yes') {
        $S.InstalledVersion = $f['INSTALLED_VERSION']
        if ($f['INSTALLED_PORT']) { $S.HttpPort = $f['INSTALLED_PORT'] }
        # Adopt the running installation's own idea of how it is reached, so Manage and
        # Update describe the system that is there rather than the defaults on this page.
        if ($f['INSTALLED_TLS'] -eq 'true') {
            $S.Tls = 'self'   # self or Lets Encrypt - the address is the same either way
            if ($f['INSTALLED_PUBLIC']) { $S.PublicHost = $f['INSTALLED_PUBLIC'] }
        } else {
            $S.Tls = 'off'
            $S.PublicHost = $S.SshHost
        }
        $was = if ($S.InstalledVersion) { "version $($S.InstalledVersion)" } else { 'an installation' }
        [void](Add-Check $lstRemote $true 'OrbixERP' "$was is already installed here")
        $grpFound.Visible = $true
        $grpFound.Text = "OrbixERP is already installed on this server ($was)"
        if ($S.InstalledVersion -and $S.InstalledVersion -eq $S.Version) {
            $rbUpdate.Enabled = $false
            $rbUpdate.Text = "Update it to this bundle - not needed, $($S.Version) is already installed"
            $rbManage.Checked = $true
        } else {
            $rbUpdate.Enabled = $true
            $rbUpdate.Text = "Update it to this bundle (version $($S.Version)). A database backup is taken first, and the update stops if that backup fails."
        }
    } else {
        [void](Add-Check $lstRemote $true 'OrbixERP' 'not installed yet - this will be a first installation')
    }

    $script:RemotePassed = $ok
    $btnNext.Enabled = $ok
    if ($ok) {
        $lblRemoteHint.Text = if ($grpFound.Visible) { 'Choose what to do below, then click Next.' } else { 'The server is ready. Click Next.' }
    } else {
        $lblRemoteHint.Text = 'Fix the items marked X, then click Check again. Nothing has been changed on the server.'
    }
}
$btnRecheck.Add_Click({ Invoke-RemoteChecks })

# ---------------------------------------------------------------------------
# Page 3 - organisation (identical questions to the local wizard)
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
# Page 4 - how people reach it
# ---------------------------------------------------------------------------
$pgNet = New-Page
New-Label $pgNet 'How will people reach the system?' 24 22 620 20 -Bold | Out-Null
New-Label $pgNet 'Everyone uses it through a web browser. Nothing is installed on their computers.' 24 46 620 20 -Dim | Out-Null

New-Label $pgNet 'Address people type' 24 84 150 20 | Out-Null
$txtPublic = New-TextBox $pgNet '' 180 81 350
New-Label $pgNet 'The name or IP address of this server as your staff reach it, e.g. erp.mycompany.co.tz' 180 108 460 20 -Dim | Out-Null

$rbPlain = New-Object System.Windows.Forms.RadioButton
$rbPlain.Text = 'Plain web address (http)'
$rbPlain.Location = New-Object System.Drawing.Point(24, 140); $rbPlain.Size = New-Object System.Drawing.Size(600, 22)
$rbPlain.Checked = $true; $rbPlain.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgNet.Controls.Add($rbPlain)
New-Label $pgNet 'Right for a system used inside your own office or over a private network.' 44 162 580 20 -Dim | Out-Null

New-Label $pgNet 'Port' 44 190 60 20 | Out-Null
$txtPort = New-TextBox $pgNet $S.HttpPort 108 187 80

$rbSelf = New-Object System.Windows.Forms.RadioButton
$rbSelf.Text = 'Encrypted (https), with a certificate the server makes itself'
$rbSelf.Location = New-Object System.Drawing.Point(24, 222); $rbSelf.Size = New-Object System.Drawing.Size(600, 22)
$rbSelf.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgNet.Controls.Add($rbSelf)
New-Label $pgNet 'Traffic is encrypted, but browsers will warn that the certificate is not trusted and staff must click through that warning each time on a new device.' 44 244 580 32 -Dim | Out-Null

$rbLe = New-Object System.Windows.Forms.RadioButton
$rbLe.Text = 'Encrypted (https), with a free trusted certificate from Lets Encrypt'
$rbLe.Location = New-Object System.Drawing.Point(24, 282); $rbLe.Size = New-Object System.Drawing.Size(600, 22)
$rbLe.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$pgNet.Controls.Add($rbLe)
New-Label $pgNet 'No warnings, but it only works if the address above is a real domain name pointed at this server, and ports 80 and 443 are open to the internet. Renewal is automatic.' 44 304 580 32 -Dim | Out-Null

New-Label $pgNet 'Whichever you choose, the port has to be open in the server firewall or cloud security group. That is done in your hosting provider, not here.' 24 350 620 36 -Dim | Out-Null

$rbPlain.Add_CheckedChanged({ $txtPort.Enabled = $rbPlain.Checked })

# ---------------------------------------------------------------------------
# Page 5 - review
# ---------------------------------------------------------------------------
$pgReview = New-Page
New-Label $pgReview 'Ready to go' 24 22 620 20 -Bold | Out-Null
New-Label $pgReview 'Check these details. Nothing has been changed on the server yet.' 24 46 620 20 -Dim | Out-Null
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
New-Label $pgWork 'Working' 24 22 620 20 -Bold | Out-Null
$lblStep = New-Label $pgWork 'Starting...' 24 46 620 20 -Dim
$bar = New-Object System.Windows.Forms.ProgressBar
$bar.Location = New-Object System.Drawing.Point(24, 74); $bar.Size = New-Object System.Drawing.Size(620, 16)
$bar.Minimum = 0; $bar.Maximum = 7
$pgWork.Controls.Add($bar)
$barFile = New-Object System.Windows.Forms.ProgressBar
$barFile.Location = New-Object System.Drawing.Point(24, 94); $barFile.Size = New-Object System.Drawing.Size(620, 10)
$barFile.Minimum = 0; $barFile.Maximum = 1000; $barFile.Visible = $false
$pgWork.Controls.Add($barFile)
$script:LogBox = New-Object System.Windows.Forms.TextBox
$script:LogBox.Multiline = $true; $script:LogBox.ReadOnly = $true; $script:LogBox.ScrollBars = 'Vertical'
$script:LogBox.Location = New-Object System.Drawing.Point(24, 112)
$script:LogBox.Size = New-Object System.Drawing.Size(620, 272)
$script:LogBox.Font = New-Object System.Drawing.Font('Consolas', 8)
$script:LogBox.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$pgWork.Controls.Add($script:LogBox)
New-Label $pgWork 'Uploading takes as long as your connection to the server needs. The first start then creates the database structure and can take several minutes.' 24 392 620 32 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Page 7 - done
# ---------------------------------------------------------------------------
$pgDone = New-Page
$lblDoneTitle = New-Label $pgDone 'OrbixERP is installed and running' 24 22 620 24 -Bold
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
$btnOpen.Add_Click({ Start-Process (Get-AccessUrl) })

$btnManageNow = New-Object System.Windows.Forms.Button
$btnManageNow.Text = 'Open control panel'; $btnManageNow.Size = New-Object System.Drawing.Size(150, 30)
$btnManageNow.Location = New-Object System.Drawing.Point(304, 318); $btnManageNow.FlatStyle = 'System'
$pgDone.Controls.Add($btnManageNow)
$btnManageNow.Add_Click({ Show-Page 8 })

New-Label $pgDone 'Back up these three together - a database backup alone cannot be signed into: the backups folder, the .env file, and the secrets folder. All three are in the install folder on the server.' 24 356 620 44 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Page 8 - manage an existing server
# ---------------------------------------------------------------------------
$pgManage = New-Page
$lblManageTitle = New-Label $pgManage 'Control panel' 24 18 620 20 -Bold
$lblManageSub = New-Label $pgManage '' 24 42 620 20 -Dim

function New-ManageButton {
    param([string]$Text, [int]$X, [int]$Y, [int]$W = 140)
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $Text
    $b.Size = New-Object System.Drawing.Size($W, 30)
    $b.Location = New-Object System.Drawing.Point($X, $Y)
    $b.FlatStyle = 'System'
    $pgManage.Controls.Add($b)
    return $b
}
$btnMStatus  = New-ManageButton 'Is it running?'      24  70
$btnMLogs    = New-ManageButton 'Recent activity'    172  70
$btnMBackup  = New-ManageButton 'Back up now'        320  70
$btnMRestart = New-ManageButton 'Restart'            468  70 84
$btnMUpdate  = New-ManageButton 'Update to this bundle' 24 106 216
$btnMStop    = New-ManageButton 'Stop the system'    248 106 140
$btnMOpen    = New-ManageButton 'Open in browser'    396 106 156
$btnMStart   = New-ManageButton 'Start the system'    24 142 156
$btnMEnv     = New-ManageButton 'Settings (.env)'    188 142 156
$btnMRestore = New-ManageButton 'Restore a backup'   352 142 156

$script:ManageLog = New-Object System.Windows.Forms.TextBox
$script:ManageLog.Multiline = $true; $script:ManageLog.ReadOnly = $true; $script:ManageLog.ScrollBars = 'Vertical'
$script:ManageLog.Location = New-Object System.Drawing.Point(24, 182)
$script:ManageLog.Size = New-Object System.Drawing.Size(620, 204)
$script:ManageLog.Font = New-Object System.Drawing.Font('Consolas', 8)
$script:ManageLog.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$pgManage.Controls.Add($script:ManageLog)
New-Label $pgManage 'Restoring replaces the database and loses everything recorded since that backup. A backup of what is being replaced is taken first, and the word RESTORE has to be typed.' 24 394 620 32 -Dim | Out-Null

# ---------------------------------------------------------------------------
# Navigation
# ---------------------------------------------------------------------------
$pages = @(
    @{ Page = $pgWelcome; Title = 'Welcome';        Sub = 'Checking this computer' }
    @{ Page = $pgServer;  Title = 'Your server';    Sub = 'Where it is and how we sign in' }
    @{ Page = $pgCheck;   Title = 'Server checks';  Sub = 'Nothing is changed by these' }
    @{ Page = $pgOrg;     Title = 'Your organisation'; Sub = 'Used to create your company and first administrator' }
    @{ Page = $pgNet;     Title = 'Access';         Sub = 'How people will reach the system' }
    @{ Page = $pgReview;  Title = 'Review';         Sub = 'Confirm before anything is changed' }
    @{ Page = $pgWork;    Title = 'Working';        Sub = 'This takes a while - leave the window open' }
    @{ Page = $pgDone;    Title = 'Finished';       Sub = 'Write down the password below' }
    @{ Page = $pgManage;  Title = 'Control panel'; Sub = 'Run, check and look after this server' }
)
$script:Index = 0
$script:Working = $false

function Show-Page {
    param([int]$i)
    $script:Index = $i
    foreach ($p in $pages) { $p.Page.Visible = $false }
    $pages[$i].Page.Visible = $true
    $hTitle.Text = $pages[$i].Title
    $hSub.Text   = $pages[$i].Sub
    $btnBack.Enabled = ($i -ge 1 -and $i -le 5)
    $btnNext.Enabled = $true
    $btnNext.Text = switch ($i) { 5 { if ($S.Mode -eq 'update') { 'Update' } else { 'Install' } } 7 { 'Finish' } 8 { 'Close' } default { 'Next' } }
    $btnCancel.Enabled = ($i -lt 6)
    if ($i -eq 0) { Invoke-LocalChecks }
    if ($i -eq 2) { Invoke-RemoteChecks }
    if ($i -eq 4) { $txtPort.Enabled = $rbPlain.Checked }
    if ($i -eq 8) {
        $lblManageSub.Text = "$($S.SshUser)@$($S.SshHost) - $($S.RemoteDir)"
        $btnNext.Text = 'Close'
    }
}

function Save-Page {
    param([int]$i)
    switch ($i) {
        1 {
            $h = $txtHost.Text.Trim()
            if (-not $h) { [System.Windows.Forms.MessageBox]::Show('Enter the address of the server.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false }
            if ($h -match '[^A-Za-z0-9._:-]') {
                [System.Windows.Forms.MessageBox]::Show("That does not look like a server address. Enter a name such as erp.mycompany.co.tz or an address such as 16.170.11.41 - not a whole ssh command.", 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            $n = 0
            if (-not ([int]::TryParse($txtSshPort.Text.Trim(), [ref]$n)) -or $n -lt 1 -or $n -gt 65535) {
                [System.Windows.Forms.MessageBox]::Show('The SSH port must be a number between 1 and 65535. It is 22 unless somebody changed it.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            $u = $txtUser.Text.Trim()
            if (-not (Test-RemoteUser $u)) {
                [System.Windows.Forms.MessageBox]::Show('Enter the user name you sign in to the server with, such as ubuntu or ec2-user.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            $kf = $txtKey.Text.Trim().Trim('"')
            $k = Test-KeyFile $kf
            if (-not $k.Ok -and -not $k.CanFix) {
                [System.Windows.Forms.MessageBox]::Show($k.Reason + "`n`nChoose the private key file for this server. It is the file your hosting provider gave you when the server was created, usually ending in .pem.", 'OrbixERP - key file', 'OK', 'Warning') | Out-Null
                return $false
            }
            $d = $txtRemoteDir.Text.Trim().TrimEnd('/')
            if (-not (Test-RemotePath $d)) {
                [System.Windows.Forms.MessageBox]::Show("Enter a full path on the server, starting with a slash and with no spaces - for example /opt/orbixerp.", 'OrbixERP - install folder', 'OK', 'Warning') | Out-Null
                return $false
            }
            $safe = Test-RemoteDirSafe $d
            if (-not $safe.Ok) {
                [System.Windows.Forms.MessageBox]::Show($safe.Reason, 'OrbixERP - choose a different folder', 'OK', 'Warning') | Out-Null
                return $false
            }
            $S.SshHost = $h; $S.SshPort = "$n"; $S.SshUser = $u; $S.KeyFile = $kf; $S.RemoteDir = $d
            if (-not $S.PublicHost) { $S.PublicHost = $h }
        }
        2 {
            if (-not $script:RemotePassed) { return $false }
            if ($grpFound.Visible) {
                $S.Mode = if ($rbUpdate.Checked) { 'update' } else { 'manage' }
            } else {
                $S.Mode = 'install'
            }
        }
        3 {
            $S.OrgName = $txtOrg.Text.Trim(); $S.CompanyName = $txtCompany.Text.Trim()
            $S.BranchName = $txtBranch.Text.Trim(); $S.TimeZone = $cboTz.Text.Trim(); $S.Currency = $cboCur.Text.Trim().ToUpper()
            if (-not $S.OrgName -or -not $S.CompanyName -or -not $S.BranchName) {
                [System.Windows.Forms.MessageBox]::Show('Organisation, company and branch names are all needed.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
        }
        4 {
            $pub = $txtPublic.Text.Trim()
            if (-not $pub) { $pub = $S.SshHost }
            if ($pub -match '[^A-Za-z0-9._:-]') {
                [System.Windows.Forms.MessageBox]::Show('The address people type must be a plain name or IP address - no http:// and no slashes.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
            }
            $S.PublicHost = $pub
            $S.Tls = if ($rbLe.Checked) { 'letsencrypt' } elseif ($rbSelf.Checked) { 'self' } else { 'off' }

            if ($S.Tls -eq 'off') {
                $n = 0
                if (-not ([int]::TryParse($txtPort.Text.Trim(), [ref]$n)) -or $n -lt 1 -or $n -gt 65535) {
                    [System.Windows.Forms.MessageBox]::Show('Enter a port number between 1 and 65535.', 'OrbixERP', 'OK', 'Warning') | Out-Null; return $false
                }
                $S.HttpPort = "$n"
                $S.BindAddr = '0.0.0.0'
            } else {
                # With the encrypted front door in place, the plain port must not also be
                # open to the network - otherwise anyone can simply use the unencrypted one.
                $S.HttpPort = '8080'
                $S.BindAddr = '127.0.0.1'
            }
            if ($S.Tls -eq 'letsencrypt' -and $S.PublicHost -match '^\d+\.\d+\.\d+\.\d+$') {
                [System.Windows.Forms.MessageBox]::Show(
                    "A trusted certificate can only be issued for a domain name, not for an IP address.`n`n" +
                    "Either enter the domain name your staff will type - and make sure it points at this server - " +
                    "or choose one of the other two options.",
                    'OrbixERP - a domain name is needed', 'OK', 'Warning') | Out-Null
                return $false
            }
        }
    }
    return $true
}

function Get-AccessUrl {
    if ($S.Tls -eq 'off') { return "http://$($S.PublicHost):$($S.HttpPort)" }
    return "https://$($S.PublicHost)"
}

function Update-Review {
    if ($S.Mode -eq 'update') {
        $from = if ($S.InstalledVersion) { $S.InstalledVersion } else { 'an earlier version' }
        $txtReview.Text = @"
Update the installation on $($S.SshHost)

Folder              $($S.RemoteDir)
Currently installed $from
Will become         $($S.Version)

What happens

  1. This bundle is uploaded to a temporary folder on the server.
  2. A backup of your database is taken FIRST.
     If that backup fails for any reason, the update stops and nothing changes.
  3. The new application is loaded, the settings files are refreshed, and the
     system is restarted.
  4. The temporary folder is removed.

What is kept

  Your data, your settings (.env), your security keys and your existing backups
  are all left exactly as they are.

Going back

  Database changes only run forwards, so putting the older version back does NOT
  undo an update. The backup taken in step 2 is the only way back - its filename
  is shown when the update finishes. Keep it until you are satisfied.
"@ -replace "`n", "`r`n"
        return
    }

    $access = switch ($S.Tls) {
        'off'         { "http://$($S.PublicHost):$($S.HttpPort)   (not encrypted)" }
        'self'        { "https://$($S.PublicHost)   (encrypted; browsers will warn about the certificate)" }
        'letsencrypt' { "https://$($S.PublicHost)   (encrypted, trusted certificate)" }
    }
    $docker = if ($S.HasDocker) { 'already installed' } else { 'will be installed by this wizard' }
    $upload = Format-Size (Get-UploadBytes)

    $txtReview.Text = @"
Install on          $($S.SshUser)@$($S.SshHost):$($S.SshPort)
Into folder         $($S.RemoteDir)

Server              $($S.ServerOs) on $($S.ServerArch)
Docker              $docker
To upload           $upload

Web address         $access

Organisation        $($S.OrgName)
Company             $($S.CompanyName)
Main branch         $($S.BranchName)
Timezone            $($S.TimeZone)
Currency            $($S.Currency)

Version             $($S.Version) ($($S.BundleArch))

The database password is generated and written only into the server's settings
file. The administrator password is generated and shown when this finishes.
"@ -replace "`n", "`r`n"
}

# ---------------------------------------------------------------------------
# The work
# ---------------------------------------------------------------------------

# Asked for once, the first time something genuinely needs administrator rights on the
# server, and only when the server actually wants a password for sudo.
# The password is checked the moment it is given, with a remote command that does nothing.
#
# Without that check a wrong password is discovered halfway through installing Docker, and
# discovered badly: sudo -S rereads standard input for its second and third attempts, so it
# swallows the first lines of the script it was supposed to run and the failure arrives as
# syntax errors from a program nobody typed.
function Request-SudoPassword {
    if ($S.SudoMode -ne 'password') { return $true }
    if ($null -ne $script:SudoSecret) { return $true }

    while ($true) {
        $sec = Read-SecretDialog 'OrbixERP - administrator password' `
            ("This step needs administrator rights on $($S.SshHost).`r`n`r`n" +
             "Enter the password for '$($S.SshUser)' on the server (the one sudo asks for). " +
             "It is used only for this and is never saved or written down anywhere.")
        if ($null -eq $sec) { return $false }
        $script:SudoSecret = $sec

        $r = Invoke-Remote 'exit 0' -Sudo -Quiet -AllowFailure
        if ($r.Code -eq 0) { return $true }

        $script:SudoSecret = $null
        $why = ($r.Err + ' ' + $r.Out)
        $msg = if ($why -match 'not in the sudoers|not allowed to run sudo') {
            "'$($S.SshUser)' is not permitted to use administrator rights on $($S.SshHost), whatever password is given.`n`n" +
            "Ask whoever looks after the server to install Docker on it, or to grant this account sudo, then run this wizard again."
        } else {
            "That password was not accepted by the server.`n`nTry again?"
        }
        $again = [System.Windows.Forms.MessageBox]::Show($msg, 'OrbixERP - administrator password', 'RetryCancel', 'Warning')
        if ($again -ne 'Retry') { return $false }
    }
}

function Invoke-RemoteStep {
    param([string]$Script, [string]$FailMessage, [switch]$Sudo, [switch]$Quiet)
    $r = Invoke-Remote $Script -Sudo:$Sudo -Quiet:$Quiet
    if ($r.Code -ne 0) {
        $detail = $r.Err.Trim()
        if (-not $detail) { $detail = $r.Out.Trim() }
        throw ($FailMessage + $(if ($detail) { "`r`n`r`nThe server said:`r`n$detail" } else { '' }))
    }
    return $r
}

function Initialize-RemoteFolder {
    Write-Log "Preparing $($S.RemoteDir) on the server..."
    $r = Invoke-Remote (Get-MakeDirScript) -Quiet
    if ($r.Code -eq 0) { Write-Log 'Folder ready.'; return }

    if ($S.SudoMode -eq 'none') {
        throw ("Cannot create $($S.RemoteDir) as '$($S.SshUser)', and this account cannot use sudo.`r`n`r`n" +
               "Either ask for the folder to be created and given to '$($S.SshUser)', or go back and " +
               "choose a folder inside that account's home directory.")
    }
    if (-not (Request-SudoPassword)) { throw 'Administrator rights are needed to create that folder on the server.' }
    [void](Invoke-RemoteStep (Get-MakeDirSudoScript) `
        "Could not create $($S.RemoteDir) on the server, even with administrator rights." -Sudo -Quiet)
    Write-Log "Folder created and given to $($S.SshUser)."
}

function Install-RemoteDocker {
    if ($S.HasDocker -and $S.HasCompose) { Write-Log 'Docker is already installed.'; return }

    if (-not (Request-SudoPassword)) { throw 'Docker has to be installed on the server, and that needs administrator rights.' }
    Write-Log 'Installing Docker on the server. This is the one step that needs the server to reach the internet.'

    $r = Invoke-Remote (Get-DockerInstallScript) -Sudo
    switch ($r.Code) {
        0 { }
        3 { throw ("This server's Linux is not one this wizard can install Docker on automatically.`r`n`r`n" +
                   "It handles Ubuntu, Debian, Amazon Linux, Red Hat, Rocky and AlmaLinux. Install Docker " +
                   "on the server yourself - your Linux supplier will have instructions - then run this " +
                   "wizard again. Nothing has been changed.") }
        4 { throw ("Docker could not be installed. The usual reason is that the server cannot reach the " +
                   "internet, which it needs once in order to fetch Docker itself.`r`n`r`n" +
                   "Either give the server internet access briefly, or install Docker from your own " +
                   "package mirror, then run this wizard again. Everything else in this bundle is " +
                   "uploaded from this computer and needs no internet at all.") }
        5 { throw ("Docker was installed, but without the 'docker compose' command that OrbixERP needs.`r`n`r`n" +
                   "On the server, install the docker-compose-plugin package, then run this wizard again.") }
        default { throw ("Installing Docker on the server did not finish.`r`n`r`n" + $r.Err.Trim()) }
    }

    # A brand new connection, because the account's new docker group membership only
    # applies from the next login onwards.
    $check = Invoke-Remote 'docker info >/dev/null 2>&1 && docker compose version >/dev/null 2>&1 && echo DOCKER_READY' -Quiet
    if ($check.Out -notmatch 'DOCKER_READY') {
        throw ("Docker was installed but '$($S.SshUser)' still cannot use it.`r`n`r`n" +
               "On the server, run:  sudo usermod -aG docker $($S.SshUser)`r`n" +
               "then sign out of the server and run this wizard again.")
    }
    Write-Log 'Docker installed and working.'
    $S.HasDocker = $true; $S.HasCompose = $true
}

# Uploads the bundle into $TargetDir. Used for a first install and, into a temporary
# folder, for an update.
function Send-Bundle {
    param([string]$TargetDir)

    if (-not (Test-RemotePath $TargetDir)) { throw "Refusing to upload to an unexpected path: $TargetDir" }

    [void](Invoke-RemoteStep "mkdir -p '$TargetDir/images'" "Could not create $TargetDir/images on the server." -Quiet)

    # The small files first, in ONE connection. Everything except images/ - those are big
    # enough to deserve their own progress - and except any .env, which belongs to the
    # server and must never be overwritten from here.
    $small = @()
    foreach ($e in (Get-ChildItem $SourceDir -Force)) {
        if ($e.Name -eq 'images') { continue }
        if ($e.Name -eq '.env')   { continue }
        $small += $e.FullName
    }
    if ($small.Count -gt 0) {
        Write-Log 'Uploading the installation scripts, settings template and guides...'
        $r = Invoke-Piped $script:ScpExe (Get-ScpArgs $small $TargetDir) $null
        if ($r.Code -ne 0) {
            throw ("Could not upload the bundle files to the server.`r`n`r`n" + $r.Err.Trim())
        }
    }

    # Then the images, one at a time, with a real progress bar.
    $files = @(Get-ChildItem (Join-Path $SourceDir 'images') -File | Where-Object { $_.Name -like '*.tar*' })
    $barFile.Visible = $true
    $n = 0
    foreach ($f in $files) {
        $n++
        $label = "Uploading $($f.Name) ($n of $($files.Count))"
        Write-Log $label
        $remoteFile = "$TargetDir/images/$($f.Name)"
        Send-RemoteFile $f.FullName $remoteFile {
            param($sent, $total, $seconds)
            $pct = 0
            if ($total -gt 0) { $pct = [int](1000 * $sent / $total) }
            $barFile.Value = [Math]::Min(1000, $pct)
            $rate = 0
            if ($seconds -gt 0.5) { $rate = $sent / $seconds }
            $left = ''
            if ($rate -gt 0 -and $sent -lt $total) {
                $secs = [int](($total - $sent) / $rate)
                $left = ", about $([TimeSpan]::FromSeconds($secs).ToString('mm\:ss')) left"
            }
            $lblStep.Text = "$label - $(Format-Size $sent) of $(Format-Size $total) at $(Format-Size $rate)/s$left"
            [System.Windows.Forms.Application]::DoEvents()
        }

        # Prove what arrived. A truncated upload otherwise surfaces much later as a damaged
        # archive, with nothing to suggest the network was at fault.
        $local = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
        $rr = Invoke-Remote (Get-ChecksumScript $remoteFile) -Quiet
        $remote = $rr.Out.Trim().ToLower()
        if ($remote -eq 'skip') {
            Write-Log '  (the server has no sha256sum, so the upload could not be verified)'
        } elseif ($remote -ne $local) {
            throw ("$($f.Name) did not arrive intact - what reached the server does not match what " +
                   "was sent.`r`n`r`nThis is almost always a dropped connection. It is safe to run " +
                   "this wizard again.")
        } else {
            Write-Log '  verified.'
        }
    }
    $barFile.Visible = $false
    $lblStep.Text = 'Upload complete.'
}

function Start-Install {
    Show-Page 6
    $script:Working = $true
    $btnNext.Enabled = $false; $btnBack.Enabled = $false; $btnCancel.Enabled = $false
    $bar.Maximum = 7
    try {
        $bar.Value = 1; $lblStep.Text = 'Checking the server is still there...'
        Assert-ServerAlive 'it was checked a few moments ago'

        $bar.Value = 2; $lblStep.Text = 'Preparing the folder...'
        Initialize-RemoteFolder

        $bar.Value = 3; $lblStep.Text = 'Making sure Docker is available...'
        Install-RemoteDocker

        $bar.Value = 4; $lblStep.Text = 'Uploading (this is the slow part)...'
        Send-Bundle $S.RemoteDir

        $bar.Value = 5; $lblStep.Text = 'Writing the settings...'
        $S.AdminPass  = New-Password 20
        $S.DbPassword = New-Password 32
        Send-RemoteText (Get-EnvContent) "$($S.RemoteDir)/.env"
        Write-Log "Settings written to $($S.RemoteDir)/.env (readable only by $($S.SshUser))."

        if ($S.Tls -eq 'letsencrypt') {
            $r = Invoke-Remote (Get-LetsEncryptScript) -Quiet
            if ($r.Code -ne 0) {
                throw ("Could not switch the web server to a trusted certificate.`r`n`r`n" +
                       "Everything else is in place. Go back and choose one of the other two options, " +
                       "or ask your supplier about the Caddyfile on the server.")
            }
            Write-Log 'The web server will request a trusted certificate on first start.'
        }

        $bar.Value = 6; $lblStep.Text = 'Installing and starting (the first start builds the database)...'
        Write-Log ''
        Write-Log '--- the server is now running its own installer ---'
        # The clock is not decoration. install.sh marks the wait for the database with a row
        # of dots and no newline, so for several minutes there is genuinely nothing new to
        # print, and a window that has not moved is a window people close.
        $r = Invoke-Remote (Get-RunScript './install.sh --defaults') -OnTick {
            param($secs)
            $lblStep.Text = ('Installing and starting on the server - ' +
                             [TimeSpan]::FromSeconds($secs).ToString('mm\:ss') +
                             ' so far. The first start builds the database and takes several minutes.')
        }
        if ($r.Code -ne 0) {
            throw ("The installation on the server did not finish. The messages above explain why.`r`n`r`n" +
                   "Nothing needs cleaning up: it is safe to fix the problem and run this wizard again.")
        }

        $bar.Value = 7; $lblStep.Text = 'Done.'
        $script:Working = $false

        $S.Mode = 'manage'
        $extra = ''
        if ($S.Tls -eq 'letsencrypt') {
            $extra = @"

    The first visit may take a few seconds while the certificate is issued.
    If the browser reports it cannot be reached, check that $($S.PublicHost)
    really points at this server and that ports 80 and 443 are open.
"@
        } elseif ($S.Tls -eq 'self') {
            $extra = @"

    Your browser will warn that the certificate is not trusted. That is
    expected: the server issued it to itself. Choose Advanced, then continue.
"@
        }

        $lblDoneTitle.Text = 'OrbixERP is installed and running on your server'
        $txtDone.Text = @"
Open it in a browser

    $(Get-AccessUrl)
$extra
Sign in with

    username            rootadmin
    password            $($S.AdminPass)

CHANGE THAT PASSWORD after your first sign-in.
It is also saved in $($S.RemoteDir)/.env on the server.

If the address does not open, the port is almost certainly closed in the
server's firewall or your hosting provider's security group. That is set
in your provider's control panel, not here.

From now on, run this wizard again and choose Manage to check the system,
back it up, restart it or update it.
"@ -replace "`n", "`r`n"
        $btnCopy.Visible = $true
        Show-Page 7
        $hSub.Text = 'Write down the password below'
    } catch {
        $script:Working = $false
        $barFile.Visible = $false
        $lblStep.Text = 'Stopped.'
        Write-Log ''
        Write-Log "PROBLEM: $($_.Exception.Message)"
        Write-Log ''
        Write-Log 'Nothing further was changed on the server. See docs\TROUBLESHOOTING.txt, fix the'
        Write-Log 'problem, and run this wizard again - it is safe to repeat.'
        $btnCancel.Text = 'Close'; $btnCancel.Enabled = $true
    }
}

# The update is NOT reimplemented here. orbixerp.sh update already does it - safety backup
# first, abort if that backup fails, load images, refresh config, restart, wait for health,
# report the rollback file - and having one implementation means the graphical and typed
# routes cannot drift into behaving differently.
function Start-Update {
    Show-Page 6
    $script:Working = $true
    $btnNext.Enabled = $false; $btnBack.Enabled = $false; $btnCancel.Enabled = $false
    $bar.Maximum = 5
    $stage = ''
    try {
        $bar.Value = 1; $lblStep.Text = 'Checking the server is still there...'
        Assert-ServerAlive 'it was checked a few moments ago'

        $ver = $S.Version -replace '[^A-Za-z0-9._-]', ''
        if (-not $ver) { throw 'This bundle has no version number, so it cannot be used for an update.' }
        # Staged INSIDE the installation folder, not beside it: the account is known to own
        # this folder (it was just used to install), whereas its parent may well be /opt,
        # which usually belongs to root.
        $stage = "$($S.RemoteDir)/.update-$ver"

        $bar.Value = 2; $lblStep.Text = 'Uploading the new version...'
        Write-Log "Uploading version $ver to $stage"
        Send-Bundle $stage

        $bar.Value = 3; $lblStep.Text = 'Backing up, then updating (do not close this window)...'
        Write-Log ''
        Write-Log '--- the server is now running its own update ---'
        $r = Invoke-Remote (Get-RunScript ("./orbixerp.sh update '" + $stage + "'")) -OnTick {
            param($secs)
            $lblStep.Text = ('Backing up and updating on the server - ' +
                             [TimeSpan]::FromSeconds($secs).ToString('mm\:ss') +
                             ' so far. Do not close this window.')
        }
        if ($r.Code -ne 0) {
            throw ('The update did not finish. The messages above explain why, and your safety backup ' +
                   'is in the backups folder on the server.')
        }

        $bar.Value = 4; $lblStep.Text = 'Tidying up...'
        # Guarded rather than trusted: the path is one this wizard built, and the guard says
        # so. An rm -rf is never issued against a path that does not carry that marker.
        if ($stage -match '/\.update-' -and (Test-RemotePath $stage)) {
            [void](Invoke-Remote ("rm -rf '" + $stage + "'") -Quiet)
            Write-Log 'Temporary upload folder removed from the server.'
        }

        $bar.Value = 5; $lblStep.Text = 'Done.'
        $script:Working = $false

        $backup = 'the newest file in the backups folder'
        $lastB = Invoke-Remote ("ls -1t '" + $S.RemoteDir + "'/backups/*.dump 2>/dev/null | head -1") -Quiet
        if ($lastB.Out.Trim()) { $backup = $lastB.Out.Trim() }

        $S.Mode = 'manage'
        $lblDoneTitle.Text = 'Your server has been updated'
        $txtDone.Text = @"
OrbixERP on $($S.SshHost) has been updated to version $($S.Version).

Open it in a browser

    $(Get-AccessUrl)

Sign in exactly as before - your users, passwords and data are unchanged.

If anything is wrong with this version

    A backup was taken before the update:
        $backup

    Database changes only run forwards, so putting the older version back does
    NOT undo the update. Restoring that backup is the only way back, and it has
    to be done on the server itself:

        cd $($S.RemoteDir)
        ./orbixerp.sh restore $backup

    Keep it until you are satisfied this version is behaving.
"@ -replace "`n", "`r`n"
        $btnCopy.Visible = $false
        Show-Page 7
        $hSub.Text = 'Your data and settings were kept'
    } catch {
        $script:Working = $false
        $barFile.Visible = $false
        $lblStep.Text = 'Update stopped.'
        Write-Log ''
        Write-Log "PROBLEM: $($_.Exception.Message)"
        Write-Log ''
        Write-Log 'Your installation has NOT been left half-updated: the update takes a backup before'
        Write-Log 'it changes anything, and stops at the first failure.'
        $btnCancel.Text = 'Close'; $btnCancel.Enabled = $true
    }
}

# ---------------------------------------------------------------------------
# Manage
# ---------------------------------------------------------------------------
function Write-ManageLog {
    param([string]$Text)
    $script:ManageLog.AppendText($Text.TrimEnd() + "`r`n")
    $script:ManageLog.SelectionStart = $script:ManageLog.TextLength
    $script:ManageLog.ScrollToCaret()
    [System.Windows.Forms.Application]::DoEvents()
}

function Invoke-ManageCommand {
    param([string]$Command, [string]$Title)
    $script:ManageLog.Clear()
    Write-ManageLog "$Title ..."
    Write-ManageLog ''

    # The manage page has its own log box, so point the shared writer at it for the
    # duration of the command and put it back afterwards.
    $keep = $script:LogBox
    $script:LogBox = $script:ManageLog
    foreach ($b in @($btnMStatus, $btnMLogs, $btnMBackup, $btnMRestart, $btnMUpdate, $btnMStop, $btnMOpen, $btnMStart, $btnMEnv, $btnMRestore)) { $b.Enabled = $false }
    $was = $lblManageSub.Text
    try {
        # A backup of a real database is not quick, and this page has no progress bar - so
        # the clock is the only thing telling the operator it is still working.
        $r = Invoke-Remote (Get-RunScript $Command) -OnTick {
            param($secs)
            $lblManageSub.Text = "$Title - $([TimeSpan]::FromSeconds($secs).ToString('mm\:ss')) so far..."
        }
        if ($r.Code -ne 0) {
            Write-ManageLog ''
            Write-ManageLog "That did not work. The messages above say why; docs\TROUBLESHOOTING.txt covers the usual causes."
        }
    } catch {
        Write-ManageLog ''
        Write-ManageLog "PROBLEM: $($_.Exception.Message)"
    } finally {
        $script:LogBox = $keep
        $lblManageSub.Text = $was
        foreach ($b in @($btnMStatus, $btnMLogs, $btnMBackup, $btnMRestart, $btnMUpdate, $btnMStop, $btnMOpen, $btnMStart, $btnMEnv, $btnMRestore)) { $b.Enabled = $true }
    }
}

$btnMStatus.Add_Click({  Invoke-ManageCommand './orbixerp.sh status'  'Checking the system' })
$btnMLogs.Add_Click({    Invoke-ManageCommand './orbixerp.sh logs'    'Recent activity' })
$btnMRestart.Add_Click({ Invoke-ManageCommand './orbixerp.sh restart' 'Restarting' })
$btnMBackup.Add_Click({
    Invoke-ManageCommand './orbixerp.sh backup' 'Backing up the database'
    Write-ManageLog ''
    Write-ManageLog 'The backup is in the backups folder on the server. Copy it somewhere else as well -'
    Write-ManageLog 'a backup kept only on the server will not survive that server failing.'
})
$btnMStop.Add_Click({
    $r = [System.Windows.Forms.MessageBox]::Show(
        "Stop OrbixERP on $($S.SshHost)?`n`nNobody will be able to use it until it is started again. " +
        "Your data is kept - nothing is deleted.",
        'OrbixERP - stop the system', 'YesNo', 'Warning')
    if ($r -eq 'Yes') { Invoke-ManageCommand './orbixerp.sh stop' 'Stopping' }
})
$btnMOpen.Add_Click({ Start-Process (Get-AccessUrl) })
$btnMStart.Add_Click({ Invoke-ManageCommand './orbixerp.sh start' 'Starting' })

# ---------------------------------------------------------------------------
# Settings (.env)
#
# The settings file is the only place the server keeps its passwords, so it is read
# and written down the SSH pipe rather than copied to this PC - nothing lands in a temp
# file here, and nothing appears on a command line the server's other users could read
# in `ps`.
#
# Two values are refused rather than warned about. ERP_DB_PASSWORD is the important one:
# changing it here does NOT change the password inside a database that already exists, it
# only stops the application being able to open it - and the way back is not obvious to
# somebody who has just locked themselves out. ERP_DB_MODE decides whether the database
# lives in a container or on the host, and switching it on a running system points the
# application at an empty one.
# ---------------------------------------------------------------------------
$script:EnvLocked = @('ERP_DB_PASSWORD', 'ERP_DB_MODE', 'ERP_DB_NAME', 'ERP_DB_USER')

function Get-EnvValue {
    param([string]$Text, [string]$Key)
    foreach ($line in ($Text -split "`n")) {
        if ($line -match ("^\s*" + [regex]::Escape($Key) + "\s*=\s*(.*)$")) { return $Matches[1].Trim() }
    }
    return $null
}

function Show-EnvEditor {
    $envPath = "$($S.RemoteDir)/.env"
    $script:ManageLog.Clear()
    Write-ManageLog "Reading $envPath ..."

    $before = $null
    try {
        $r = Invoke-Piped $script:SshExe (Get-SshArgs ('"cat ' + "'$envPath'" + '"')) $null -Quiet
        if ($r.Code -ne 0) {
            Write-ManageLog ''
            Write-ManageLog "Could not read the settings file. $($r.Err.Trim())"
            return
        }
        $before = ($r.Out -replace "`r`n", "`n")
    } catch {
        Write-ManageLog "PROBLEM: $($_.Exception.Message)"
        return
    }

    $dlg = New-Object System.Windows.Forms.Form
    $dlg.Text = "OrbixERP settings - $($S.SshHost)"
    $dlg.Size = New-Object System.Drawing.Size(760, 560)
    $dlg.StartPosition = 'CenterParent'
    $dlg.MinimizeBox = $false; $dlg.MaximizeBox = $false

    $warn = New-Object System.Windows.Forms.Label
    $warn.Text = "This file holds your passwords. Changes take effect when the system is restarted." + [Environment]::NewLine +
                 "The database lines are shown but cannot be changed here - changing them locks the system out of its own data."
    $warn.Location = New-Object System.Drawing.Point(16, 12)
    $warn.Size = New-Object System.Drawing.Size(710, 34)
    $dlg.Controls.Add($warn)

    $box = New-Object System.Windows.Forms.TextBox
    $box.Multiline = $true; $box.ScrollBars = 'Both'; $box.WordWrap = $false
    $box.Font = New-Object System.Drawing.Font('Consolas', 9)
    $box.Location = New-Object System.Drawing.Point(16, 52)
    $box.Size = New-Object System.Drawing.Size(710, 410)
    $box.Text = ($before -replace "`n", [Environment]::NewLine)
    $dlg.Controls.Add($box)

    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = 'Save'; $ok.Size = New-Object System.Drawing.Size(100, 30)
    $ok.Location = New-Object System.Drawing.Point(510, 474); $ok.FlatStyle = 'System'
    $dlg.Controls.Add($ok)

    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = 'Cancel'; $cancel.Size = New-Object System.Drawing.Size(100, 30)
    $cancel.Location = New-Object System.Drawing.Point(624, 474); $cancel.FlatStyle = 'System'
    $cancel.DialogResult = 'Cancel'
    $dlg.Controls.Add($cancel)
    $dlg.CancelButton = $cancel

    $ok.Add_Click({
        $after = ($box.Text -replace "`r`n", "`n")

        # Refuse the locked keys BEFORE anything is written. Comparing values rather than
        # whole lines means reformatting or reordering the file is still allowed.
        foreach ($k in $script:EnvLocked) {
            $a = Get-EnvValue $before $k
            $b = Get-EnvValue $after  $k
            if ($a -ne $b) {
                [System.Windows.Forms.MessageBox]::Show(
                    "$k cannot be changed here." + [Environment]::NewLine + [Environment]::NewLine +
                    "This line has to match the database that already exists on the server. Changing it " +
                    "does not change the database - it only stops OrbixERP being able to open it, and the " +
                    "system stops working until the old value is put back." + [Environment]::NewLine + [Environment]::NewLine +
                    "Put that line back as it was, then save.",
                    'OrbixERP - that line is protected', 'OK', 'Warning') | Out-Null
                return
            }
        }
        if ($after -notmatch '\S') {
            [System.Windows.Forms.MessageBox]::Show('The settings file cannot be emptied.', 'OrbixERP', 'OK', 'Warning') | Out-Null
            return
        }
        $dlg.Tag = $after
        $dlg.DialogResult = 'OK'
    })

    $res = $dlg.ShowDialog()
    $new = $dlg.Tag
    $dlg.Dispose()
    if ($res -ne 'OK' -or -not $new) { Write-ManageLog 'No changes were made.'; return }
    if ($new -eq $before) { Write-ManageLog 'Nothing was changed.'; return }

    try {
        # Keep the previous file. Somebody who mistypes a line needs a way back that does
        # not depend on remembering what it said.
        $stamp = (Get-Date).ToString('yyyyMMdd_HHmmss')
        Write-ManageLog "Keeping a copy of the old settings as .env.bak-$stamp"
        $r = Invoke-Remote "cd '$($S.RemoteDir)' && cp -p .env '.env.bak-$stamp'" -Quiet
        if ($r.Code -ne 0) { Write-ManageLog 'Could not save a copy of the old settings - nothing was changed.'; return }

        Send-RemoteText $new "$($S.RemoteDir)/.env"
        Write-ManageLog 'Saved.'
        Write-ManageLog ''
    } catch {
        Write-ManageLog "PROBLEM: $($_.Exception.Message)"
        return
    }

    $r = [System.Windows.Forms.MessageBox]::Show(
        "Settings saved." + [Environment]::NewLine + [Environment]::NewLine +
        "They do not take effect until OrbixERP is restarted. Restart it now?" + [Environment]::NewLine + [Environment]::NewLine +
        "Nobody can use the system for a few seconds while it restarts.",
        'OrbixERP - restart now?', 'YesNo', 'Question')
    if ($r -eq 'Yes') { Invoke-ManageCommand './orbixerp.sh restart' 'Restarting' }
    else { Write-ManageLog 'Remember to restart before the new settings are used.' }
}

$btnMEnv.Add_Click({ Show-EnvEditor })

# ---------------------------------------------------------------------------
# Restore
#
# The one irreversible thing in this window, and the reason it is here rather than left to
# a terminal: restoring is what you do when moving a system to a new server, and a cutover
# is exactly the wrong moment to be typing commands by hand under time pressure. So it is
# offered, but with the two things a hand-typed restore usually lacks - a backup of what is
# about to be replaced, taken automatically first, and a confirmation you have to type.
#
# orbixerp.sh reads its own confirmation from /dev/tty and treats "no terminal" as a
# refusal. A plain non-interactive session would therefore cancel silently every time and
# look like nothing happened, so a terminal is allocated deliberately and the answer typed
# into it.
# ---------------------------------------------------------------------------
function Get-SshTtyArgs {
    param([string]$Remote = '')
    $a = @('-tt', '-i', ('"' + $S.KeyFile + '"'), '-p', $S.SshPort)
    $a += Get-CommonSshOptions
    $a += ("$($S.SshUser)@$($S.SshHost)")
    if ($Remote) { $a += $Remote }
    return ($a -join ' ')
}

function Read-TypedConfirmation {
    param([string]$Word, [string]$Message)
    $d = New-Object System.Windows.Forms.Form
    $d.Text = 'OrbixERP - confirm'; $d.Size = New-Object System.Drawing.Size(560, 260)
    $d.StartPosition = 'CenterParent'; $d.MinimizeBox = $false; $d.MaximizeBox = $false; $d.FormBorderStyle = 'FixedDialog'

    $l = New-Object System.Windows.Forms.Label
    $l.Text = $Message; $l.Location = New-Object System.Drawing.Point(16, 16); $l.Size = New-Object System.Drawing.Size(510, 120)
    $d.Controls.Add($l)

    $p = New-Object System.Windows.Forms.Label
    $p.Text = "Type $Word to continue:"; $p.Location = New-Object System.Drawing.Point(16, 140); $p.Size = New-Object System.Drawing.Size(180, 20)
    $d.Controls.Add($p)

    $t = New-Object System.Windows.Forms.TextBox
    $t.Location = New-Object System.Drawing.Point(196, 138); $t.Size = New-Object System.Drawing.Size(160, 24)
    $d.Controls.Add($t)

    $go = New-Object System.Windows.Forms.Button
    $go.Text = 'Restore'; $go.Size = New-Object System.Drawing.Size(100, 30)
    $go.Location = New-Object System.Drawing.Point(320, 178); $go.FlatStyle = 'System'; $go.Enabled = $false
    $d.Controls.Add($go)

    $no = New-Object System.Windows.Forms.Button
    $no.Text = 'Cancel'; $no.Size = New-Object System.Drawing.Size(100, 30)
    $no.Location = New-Object System.Drawing.Point(428, 178); $no.FlatStyle = 'System'; $no.DialogResult = 'Cancel'
    $d.Controls.Add($no); $d.CancelButton = $no

    # Enabled only on an exact match, so the word has to be typed rather than guessed at.
    $t.Add_TextChanged({ $go.Enabled = ($t.Text -ceq $Word) })
    $go.Add_Click({ $d.DialogResult = 'OK' })

    $r = $d.ShowDialog()
    $d.Dispose()
    return ($r -eq 'OK')
}

function Start-Restore {
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Title = 'Choose the backup to restore'
    $dlg.Filter = 'Database backups (*.dump)|*.dump|All files (*.*)|*.*'
    if ($dlg.ShowDialog() -ne 'OK') { return }
    $local = $dlg.FileName

    # The name reaches a shell command line on the server, so anything outside this set is
    # replaced rather than quoted around. A backup called `x;rm -rf /.dump` is not a file
    # this window is going to pass along verbatim.
    $safe = [System.IO.Path]::GetFileName($local) -replace '[^A-Za-z0-9._-]', '_'
    if ($safe -notmatch '\.dump$') { $safe = "$safe.dump" }
    $sizeText = Format-Size ((Get-Item $local).Length)

    $okToGo = Read-TypedConfirmation 'RESTORE' (
        "This REPLACES the database on $($S.SshHost) with the contents of:" + [Environment]::NewLine + [Environment]::NewLine +
        "    $safe  ($sizeText)" + [Environment]::NewLine + [Environment]::NewLine +
        "Everything recorded on that server since this backup was taken will be lost." + [Environment]::NewLine + [Environment]::NewLine +
        "A backup of the current database is taken first, so there is a way back.")
    if (-not $okToGo) { $script:ManageLog.Clear(); Write-ManageLog 'Restore cancelled. Nothing was changed.'; return }

    $script:ManageLog.Clear()
    $keep = $script:LogBox
    $script:LogBox = $script:ManageLog
    foreach ($b in @($btnMStatus, $btnMLogs, $btnMBackup, $btnMRestart, $btnMUpdate, $btnMStop, $btnMOpen, $btnMStart, $btnMEnv, $btnMRestore)) { $b.Enabled = $false }
    $was = $lblManageSub.Text
    try {
        Write-ManageLog 'Step 1 of 3 - backing up the database that is about to be replaced'
        $r = Invoke-Remote (Get-RunScript './orbixerp.sh backup') -OnTick {
            param($secs) $lblManageSub.Text = "Backing up first - $([TimeSpan]::FromSeconds($secs).ToString('mm\:ss'))"
        }
        if ($r.Code -ne 0) {
            Write-ManageLog ''
            Write-ManageLog 'The safety backup failed, so nothing was restored. The database is untouched.'
            return
        }

        Write-ManageLog ''
        Write-ManageLog "Step 2 of 3 - sending $safe ($sizeText)"
        Send-RemoteFile $local "$($S.RemoteDir)/backups/$safe" -OnProgress {
            param($sent, $total, $secs)
            $pct = if ($total -gt 0) { [int](100 * $sent / $total) } else { 0 }
            $rate = if ($secs -gt 0) { Format-Size ($sent / $secs) } else { '' }
            $lblManageSub.Text = "Sending the backup - $pct%$(if ($rate) { " ($rate/s)" })"
        }

        Write-ManageLog ''
        Write-ManageLog 'Step 3 of 3 - restoring'
        $cmd = "cd '$($S.RemoteDir)' && bash ./orbixerp.sh restore 'backups/$safe'"
        $rr = Invoke-Piped $script:SshExe (Get-SshTtyArgs ('"' + $cmd + '"')) {
            param($p)
            # Typed into the allocated terminal. The prompt may not have appeared yet; a
            # terminal buffers what is typed early, so this is read when it is reached.
            $p.StandardInput.WriteLine('RESTORE')
            $p.StandardInput.Flush()
        }
        if ($rr.Out) { Write-ManageLog ($rr.Out.TrimEnd()) }
        if ($rr.Code -ne 0) {
            Write-ManageLog ''
            Write-ManageLog 'The restore did not finish. The messages above say why.'
            Write-ManageLog 'The database is whatever the restore left behind - the safety backup from step 1'
            Write-ManageLog 'is in the backups folder on the server if you need to go back.'
            return
        }
        Write-ManageLog ''
        Write-ManageLog 'Restored.'
        Write-ManageLog ''
        Write-ManageLog 'Sign in with the accounts from the system this backup came from - the ones that'
        Write-ManageLog 'belonged to this server were replaced along with the rest of the database.'
        Write-ManageLog 'If nobody can sign in at all, the secrets folder from the other system has not'
        Write-ManageLog 'been copied across; the data is fine, the sign-in keys are not.'
    } catch {
        Write-ManageLog ''
        Write-ManageLog "PROBLEM: $($_.Exception.Message)"
    } finally {
        $script:LogBox = $keep
        $lblManageSub.Text = $was
        foreach ($b in @($btnMStatus, $btnMLogs, $btnMBackup, $btnMRestart, $btnMUpdate, $btnMStop, $btnMOpen, $btnMStart, $btnMEnv, $btnMRestore)) { $b.Enabled = $true }
    }
}

$btnMRestore.Add_Click({ Start-Restore })
$btnMUpdate.Add_Click({
    if ($S.InstalledVersion -and $S.Version -and $S.InstalledVersion -eq $S.Version) {
        [System.Windows.Forms.MessageBox]::Show(
            "Version $($S.Version) is already installed on that server - it is the same version as this " +
            "bundle, so there is nothing to update.",
            'OrbixERP - already up to date', 'OK', 'Information') | Out-Null
        return
    }
    $r = [System.Windows.Forms.MessageBox]::Show(
        "Update $($S.SshHost) to version $($S.Version)?`n`n" +
        "A backup of your database is taken FIRST, and the update stops if that backup fails. " +
        "Your data, settings and security keys are kept.",
        'OrbixERP - update', 'YesNo', 'Question')
    if ($r -eq 'Yes') { $S.Mode = 'update'; Start-Update }
})

# ---------------------------------------------------------------------------
$btnNext.Add_Click({
    if ($script:Index -eq 7) { $form.Close(); return }
    if ($script:Index -eq 8) { $form.Close(); return }
    if ($script:Index -eq 5) {
        if ($S.Mode -eq 'update') { Start-Update } else { Start-Install }
        return
    }
    if (-not (Save-Page $script:Index)) { return }

    if ($script:Index -eq 2) {
        if ($S.Mode -eq 'manage') { Show-Page 8; return }
        if ($S.Mode -eq 'update') { Update-Review; Show-Page 5; return }
    }
    if ($script:Index -eq 4) { Update-Review }
    Show-Page ($script:Index + 1)
})
$btnBack.Add_Click({
    if ($script:Index -eq 5 -and $S.Mode -eq 'update') { Show-Page 2; return }
    if ($script:Index -gt 0) { Show-Page ($script:Index - 1) }
})
$btnCancel.Add_Click({ $form.Close() })

# Cancel is disabled while working, but the X in the title bar is not - and closing the
# window mid-way kills the wizard between steps, which for a remote install means files
# uploaded but nothing started.
$form.Add_FormClosing({
    # Not named $sender - that is a PowerShell automatic variable.
    param($src, $ev)
    if ($script:Working) {
        $r = [System.Windows.Forms.MessageBox]::Show(
            "Work on the server is still running.`n`nClosing now would stop it part-way. Nothing on the " +
            "server is deleted, but it may be left not yet started.`n`nClose anyway?",
            'OrbixERP - still working', 'YesNo', 'Warning')
        if ($r -ne 'Yes') { $ev.Cancel = $true }
    }
})

Show-Page 0
[void]$form.ShowDialog()
