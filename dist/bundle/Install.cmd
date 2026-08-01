@echo off
REM ===========================================================================
REM  OrbixERP - installer for Windows.
REM
REM  DOUBLE-CLICK THIS FILE. Nothing else is needed.
REM
REM  Why this file exists: Windows blocks PowerShell scripts that arrived from
REM  the internet, so install.ps1 cannot simply be double-clicked. A .cmd file
REM  has no such restriction, so this one starts PowerShell with the block
REM  lifted for this one script only. Nothing is changed permanently on the
REM  machine.
REM
REM  Keep this file next to install.ps1 - it will not work on its own.
REM ===========================================================================

title OrbixERP - Installation

REM Double-clicking can start in a different folder, so move to this file's own
REM folder before doing anything. %~dp0 is that folder, quoted for paths with spaces.
cd /d "%~dp0"

if not exist "%~dp0install.ps1" (
    echo.
    echo   PROBLEM: install.ps1 was not found next to this file.
    echo.
    echo   This installation folder looks incomplete. Unpack the whole
    echo   bundle again, keeping every file together, then try once more.
    echo.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set ERPEXIT=%ERRORLEVEL%

echo.
if not "%ERPEXIT%"=="0" (
    echo   ---------------------------------------------------------------
    echo    The installation did not finish.
    echo.
    echo    Read the message above - it says what to fix. Most problems are
    echo    covered in  docs\TROUBLESHOOTING.txt
    echo.
    echo    Nothing was damaged. It is safe to fix the problem and run this
    echo    file again.
    echo   ---------------------------------------------------------------
) else (
    echo   ---------------------------------------------------------------
    echo    WRITE DOWN THE PASSWORD SHOWN ABOVE before closing this window.
    echo.
    echo    From now on, use  OrbixERP.cmd  to start, stop and back up the system.
    echo   ---------------------------------------------------------------
)
echo.

REM Without this the window closes instantly and the password is lost.
pause
exit /b %ERPEXIT%
