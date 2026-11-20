@echo off
rem Builds the Orbix App PWA. Double-click this file (or right-click, Open).
rem Runs the tests, builds the web app and zips it to dist\orbixapp\.
rem Options still work from a terminal:  build-pwa.cmd -SkipTests
rem Everything else lives in build-pwa.ps1 beside this file.
setlocal
title Orbix App PWA build
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-pwa.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (echo Build finished.) else (echo Build FAILED - read the messages above.)
rem Keep the window open when started from Explorer, so the result can be read.
echo %CMDCMDLINE% | find /i "%~nx0" >nul && pause
exit /b %RC%
