@echo off
REM ===========================================================================
REM  OrbixERP - everyday tasks, for Windows.
REM
REM  DOUBLE-CLICK THIS FILE, then pick a number.
REM
REM  Everything here also exists as typed commands (orbixerp.ps1) for anyone who
REM  prefers them. This menu is simply the same thing without the typing.
REM
REM  Keep this file next to orbixerp.ps1 - it will not work on its own.
REM ===========================================================================

title OrbixERP

REM Double-clicking can start in a different folder, so move to this file's own
REM folder before doing anything.
cd /d "%~dp0"

if not exist "%~dp0erp.ps1" (
    echo.
    echo   PROBLEM: orbixerp.ps1 was not found next to this file.
    echo   This folder looks incomplete. Unpack the bundle again.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0.env" (
    echo.
    echo   The system has not been installed on this computer yet.
    echo.
    echo   Double-click  Install.cmd  first.
    echo.
    pause
    exit /b 1
)

:menu
cls
echo.
echo   ===============================================================
echo     OrbixERP
echo   ===============================================================
echo.
echo     1   Start the system
echo     2   Stop the system            (your data is kept)
echo     3   Is it running?
echo     4   Back up the database
echo     5   Show recent activity
echo     6   Open it in a web browser
echo.
echo     0   Close this window
echo.
set "ERPCHOICE="
set /p ERPCHOICE=  Type a number and press Enter:

REM set /p leaves the variable untouched when the user just presses Enter,
REM which is why it is cleared immediately above.
if "%ERPCHOICE%"=="1" goto start
if "%ERPCHOICE%"=="2" goto stop
if "%ERPCHOICE%"=="3" goto status
if "%ERPCHOICE%"=="4" goto backup
if "%ERPCHOICE%"=="5" goto logs
if "%ERPCHOICE%"=="6" goto browser
if "%ERPCHOICE%"=="0" exit /b 0
goto menu

:start
call :run start
goto menu

:stop
call :run stop
goto menu

:status
call :run status
goto menu

:backup
call :run backup
echo.
echo   Copy the backups folder somewhere else as well - another computer,
echo   a USB drive, or cloud storage. A backup kept only on this machine
echo   will not survive this machine failing.
echo.
pause
goto menu

:logs
call :run logs
goto menu

:browser
REM The port lives in .env, so it is read from there rather than assumed.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='8080'; if (Test-Path '.env') { $m = Select-String -Path '.env' -Pattern '^ERP_HTTP_PORT=' | Select-Object -Last 1; if ($m) { $v = ($m.Line -split '=',2)[1].Trim(); if ($v) { $p = $v } } }; Start-Process ('http://localhost:' + $p)"
goto menu

:run
cls
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0erp.ps1" %1
echo.
if not "%ERRORLEVEL%"=="0" (
    echo   That did not work. The message above says why;
    echo   docs\TROUBLESHOOTING.txt covers the usual causes.
    echo.
)
if not "%1"=="backup" pause
exit /b 0
