@echo off
rem Builds OrbixHQ APKs. Double-click this file (or right-click, Open).
rem It asks which brand(s) to build and each server address - press Enter
rem to keep the default shown. APKs land in dist\orbixhq\.
rem Options still work from a terminal:  build-hq.cmd -Brand shayo
rem Everything else lives in build-hq.ps1 beside this file.
setlocal
title OrbixHQ build
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-hq.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (echo Build finished.) else (echo Build FAILED - read the messages above.)
rem Keep the window open when started from Explorer, so the result can be read.
echo %CMDCMDLINE% | find /i "%~nx0" >nul && pause
exit /b %RC%
