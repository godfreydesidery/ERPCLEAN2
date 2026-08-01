@echo off
REM ===========================================================================
REM  OrbixERP - Setup Wizard for Windows.
REM
REM  DOUBLE-CLICK THIS FILE. A window opens and guides you through everything:
REM  choosing a folder, settings, passwords, and starting the system.
REM
REM  Why a .cmd and not the wizard directly: Windows refuses to run PowerShell
REM  scripts that arrived from another computer. A .cmd has no such
REM  restriction, so this starts the wizard with that block lifted for this one
REM  script. Nothing on the machine is changed permanently.
REM
REM  Keep this file next to setup-wizard.ps1 - it will not work on its own.
REM ===========================================================================

title OrbixERP - Setup

cd /d "%~dp0"

if not exist "%~dp0setup-wizard.ps1" (
    echo.
    echo   PROBLEM: setup-wizard.ps1 was not found next to this file.
    echo.
    echo   This folder looks incomplete. Unpack the whole bundle again,
    echo   keeping every file together, then try once more.
    echo.
    pause
    exit /b 1
)

REM -STA is required: the Windows dialog components used by the wizard will not
REM start in a multi-threaded apartment.
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0setup-wizard.ps1"
set ERPEXIT=%ERRORLEVEL%

REM The wizard reports its own errors in the window. Only pause when it failed
REM to start at all, so a successful run does not leave a stray console behind.
if not "%ERPEXIT%"=="0" (
    echo.
    echo   The setup wizard closed unexpectedly.
    echo   docs\TROUBLESHOOTING.txt lists the usual causes.
    echo.
    pause
)
exit /b %ERPEXIT%
