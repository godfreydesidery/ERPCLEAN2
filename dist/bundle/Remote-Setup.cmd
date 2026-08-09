@echo off
REM ===========================================================================
REM  OrbixERP - Setup Wizard for a REMOTE SERVER, from this Windows PC.
REM
REM  DOUBLE-CLICK THIS FILE if OrbixERP is to run on a server somewhere else -
REM  in your server room or with a hosting provider - rather than on this
REM  computer. A window opens and guides you through everything: the server
REM  address, the key file, the settings, and the installation itself.
REM
REM  Use the same window afterwards to check the system, back it up, restart it
REM  or update it, without ever typing a command on the server.
REM
REM  To install on THIS computer instead, use Setup.cmd.
REM
REM  Why a .cmd and not the wizard directly: Windows refuses to run PowerShell
REM  scripts that arrived from another computer. A .cmd has no such
REM  restriction, so this starts the wizard with that block lifted for this one
REM  script. Nothing on the machine is changed permanently.
REM
REM  Keep this file next to remote-setup-wizard.ps1 - it will not work on its own.
REM ===========================================================================

title OrbixERP - Remote Setup

cd /d "%~dp0"

if not exist "%~dp0remote-setup-wizard.ps1" (
    echo.
    echo   PROBLEM: remote-setup-wizard.ps1 was not found next to this file.
    echo.
    echo   This folder looks incomplete. Unpack the whole bundle again,
    echo   keeping every file together, then try once more.
    echo.
    pause
    exit /b 1
)

REM The wizard talks to the server with the SSH client built into Windows. It is
REM present on Windows 10 and 11 by default, but it can be switched off - so say
REM plainly how to turn it back on rather than failing with "not recognised".
REM
REM Checked in two places, and the second matters: some machines have an ssh.exe
REM from Git for Windows or a VPN client on the PATH instead. The wizard prefers
REM the Windows one and falls back to whatever is on the PATH, so this check is
REM only allowed to refuse when there is no ssh at all.
if exist "%SystemRoot%\System32\OpenSSH\ssh.exe" goto :havessh
where ssh.exe >nul 2>&1
if not errorlevel 1 goto :havessh
echo.
echo   PROBLEM: the Windows SSH client is not enabled on this computer.
echo.
echo   Turn it on:  Settings  -  System  -  Optional features
echo                Add an optional feature  -  OpenSSH Client
echo.
echo   Nothing needs downloading; it is part of Windows. Then run this
echo   file again.
echo.
pause
exit /b 1
:havessh

REM -STA is required: the Windows dialog components used by the wizard will not
REM start in a multi-threaded apartment.
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0remote-setup-wizard.ps1"
set ERPEXIT=%ERRORLEVEL%

REM The wizard reports its own errors in the window. Only pause when it failed
REM to start at all, so a successful run does not leave a stray console behind.
if not "%ERPEXIT%"=="0" (
    echo.
    echo   The remote setup wizard closed unexpectedly.
    echo   docs\REMOTE-INSTALL.txt and docs\TROUBLESHOOTING.txt list the usual causes.
    echo.
    pause
)
exit /b %ERPEXIT%
