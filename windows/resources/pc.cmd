@echo off
powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%~dp0termant-pc.ps1" %*
exit /b %ERRORLEVEL%
