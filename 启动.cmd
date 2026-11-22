@echo off
setlocal
cd /d "%~dp0"
title Xiaoyu Phone Service

where node >nul 2>nul
if errorlevel 1 goto NONODE

echo.
echo  ================================================
echo    Xiaoyu - phone service (portable)
echo  ================================================
echo.
echo  [1/2] Building agent profile (persona + memory + tools) ...
echo.
node "server\make-profile.mjs"
if errorlevel 1 goto FAILED

echo.
echo  [2/2] Starting service ...
echo.
echo  Phone app address (same Wi-Fi):
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
  for /f "tokens=1" %%b in ("%%a") do echo      http://%%b:19401
)
echo.
echo  Settings page on this PC:  http://127.0.0.1:19401/
echo  Keep this window open. Closing it stops the service.
echo.

start "" http://127.0.0.1:19401/
node "server\server.mjs"
goto END

:NONODE
echo.
echo  Node.js not found. Install it first: https://nodejs.org
echo.
pause
exit /b 1

:FAILED
echo.
echo  Failed to build the profile. See errors above.
echo.
pause
exit /b 1

:END
pause