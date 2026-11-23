<#
    deploy-pwa-iis.ps1 - deploy or roll back the Orbix App PWA on IIS.

    SERVER TOOL: runs on the IIS server, not on the build machine. Copy this file
    to C:\deploy on the server once (WINDOWS_EC2_DEPLOYMENT.md section 4.2). For
    each release, copy OrbixApp-<version>.zip AND its OrbixApp-<version>.json from
    dist\orbixapp\ into C:\deploy, then in an Administrator PowerShell:

      C:\deploy\deploy-pwa-iis.ps1                     # newest OrbixApp-*.zip in C:\deploy
      C:\deploy\deploy-pwa-iis.ps1 -Version 1.4.3-10   # a specific one
      C:\deploy\deploy-pwa-iis.ps1 -Rollback           # back to the previous release
      C:\deploy\deploy-pwa-iis.ps1 -List               # show releases, * = live

    A file copied from another machine may be blocked: Unblock-File it once, or run
    it with  powershell -ExecutionPolicy Bypass -File C:\deploy\deploy-pwa-iis.ps1

    Each release is unpacked into C:\inetpub\orbix-app\releases\<version> and the
    IIS site "Orbix App" is pointed at it. No IIS restart is needed: users get the
    "A new version of Orbix App is ready" banner. The newest -Keep releases are kept
    for rollback (default 5).
#>
param(
    [string] $Version,
    [switch] $Rollback,
    [switch] $List,
    [int]    $Keep = 5
)
$ErrorActionPreference = 'Stop'
Import-Module WebAdministration

$site = 'IIS:\Sites\Orbix App'
$drop = 'C:\deploy'
$rels = 'C:\inetpub\orbix-app\releases'
$host_ = 'https://app.orbixsystems.app'

$live = (Get-ItemProperty $site).physicalPath
$all  = @(Get-ChildItem $rels -Directory | Where-Object Name -ne 'empty' | Sort-Object LastWriteTime)

if ($List) {
    $all | ForEach-Object { '{0} {1}' -f $(if ($_.FullName -eq $live) { '*' } else { ' ' }), $_.Name }
    return
}

if ($Rollback) {
    $prev = $all | Where-Object FullName -ne $live | Select-Object -Last 1
    if (-not $prev) { throw 'No earlier release to roll back to.' }
    Set-ItemProperty $site -Name physicalPath -Value $prev.FullName
    "Rolled back: $live -> $($prev.FullName)"
    return
}

# 1. Which zip
if ($Version) {
    $zip = Get-Item (Join-Path $drop "OrbixApp-$Version.zip")
} else {
    $zip = Get-ChildItem (Join-Path $drop 'OrbixApp-*.zip') | Sort-Object LastWriteTime | Select-Object -Last 1
}
if (-not $zip) { throw "No OrbixApp-*.zip in $drop" }
$ver = $zip.BaseName -replace '^OrbixApp-', ''
"Deploying $($zip.Name)"

# 2. Integrity: compare with the .json the build wrote beside the zip
$rec = Join-Path $drop "OrbixApp-$ver.json"
if (Test-Path $rec) {
    $want = (Get-Content $rec -Raw | ConvertFrom-Json).sha256
    $got  = (Get-FileHash $zip.FullName -Algorithm SHA256).Hash
    if ($got -ne $want) { throw "Hash mismatch for $($zip.Name) - copy it again." }
    'Hash OK'
} else {
    Write-Warning "No OrbixApp-$ver.json in $drop - the zip's hash was not checked."
}

# 3. Unpack into its own release folder
$dest = Join-Path $rels $ver
if ((Test-Path $dest) -and ($dest -eq $live)) {
    throw "$ver is already live. Deploy a new version, or -Rollback first to re-deploy it."
}
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
Expand-Archive $zip.FullName -DestinationPath $dest
if (-not (Test-Path (Join-Path $dest 'index.html'))) {
    Remove-Item $dest -Recurse -Force
    throw "The zip has no index.html - wrong file? Nothing was changed."
}

# 4. Switch the site
Set-ItemProperty $site -Name physicalPath -Value $dest
"Live: $dest"
"Was:  $live"

# 5. Keep the newest $Keep releases (never the live or the previous one)
Get-ChildItem $rels -Directory |
    Where-Object { $_.Name -ne 'empty' -and $_.FullName -ne $dest -and $_.FullName -ne $live } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip ([Math]::Max($Keep - 2, 0)) |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force; "Removed old release $($_.Name)" }

# 6. Check it is being served (sw.js carries the version, e.g. 1.4.3+10-<hash>)
$expect = $ver -replace '-(\d+)$', '+$1'
try {
    $sw = (Invoke-WebRequest "$host_/sw.js" -UseBasicParsing -Headers @{ 'Cache-Control' = 'no-cache' }).Content
    if ($sw -match [regex]::Escape($expect)) {
        "OK - $host_ serves $expect. Users get the 'new version' banner."
    } else {
        Write-Warning "$host_/sw.js does not show $expect yet (Cloudflare cache?). Open $host_/sw.js to check."
    }
} catch {
    Write-Warning "Could not reach $host_/sw.js from this server: $($_.Exception.Message)"
}
