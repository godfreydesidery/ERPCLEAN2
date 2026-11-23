@echo off
rem Orbix App (PWA) deploy - double-click this file on the IIS server.
rem Keep it in C:\deploy next to deploy-pwa-iis.ps1. It asks for administrator
rem rights (IIS needs them), offers a menu, and keeps the window open so the
rem result can be read. Everything else lives in deploy-pwa-iis.ps1.
setlocal
title Orbix App deploy

rem Re-launch elevated if not already running as administrator.
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

:menu
echo.
echo   Orbix App (PWA) - IIS
echo   ---------------------
echo   1  Deploy the newest OrbixApp zip in %~dp0
echo   2  List releases on this server (* = live)
echo   3  Roll back to the previous release
echo   Q  Quit
echo.
set "CHOICE="
set /p "CHOICE=Choose 1, 2, 3 or Q: "
if /i "%CHOICE%"=="1" set "ARGS=" & goto run
if /i "%CHOICE%"=="2" set "ARGS=-List" & goto run
if /i "%CHOICE%"=="3" goto confirm
if /i "%CHOICE%"=="Q" exit /b 0
echo Please type 1, 2, 3 or Q.
goto menu

:confirm
set "SURE="
set /p "SURE=Roll back the live app to the previous release? (Y/N): "
if /i not "%SURE%"=="Y" goto menu
set "ARGS=-Rollback"

:run
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-pwa-iis.ps1" %ARGS%
if errorlevel 1 (echo. & echo FAILED - read the message above. Nothing was changed unless it says "Live:".) else (echo. & echo Done.)
goto menu
