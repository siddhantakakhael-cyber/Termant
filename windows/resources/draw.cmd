@echo off
powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%~dp0draw.ps1" %*
exit /b %ERRORLEVEL%
