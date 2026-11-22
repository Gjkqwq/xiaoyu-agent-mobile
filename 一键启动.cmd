@echo off
setlocal
cd /d "%~dp0"
title Xiaoyu - All In One

where node >nul 2>nul
if errorlevel 1 goto NONODE

echo.
echo  ================================================
echo    Xiaoyu - start everything (service + tunnel)
echo  ================================================
echo.
echo  [1/3] Building agent profile ...
node "server\make-profile.mjs"
if errorlevel 1 goto FAILED

echo.
echo  [2/3] Starting local service ...
start "Xiaoyu Service (do not close)" /min cmd /c "node server\server.mjs"
timeout /t 5 >nul

echo.
echo  [3/3] Starting public tunnel ...
echo.
echo  Phone can connect from ANY network with the https
echo  address printed below. Home Wi-Fi can also use:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
  for /f "tokens=1" %%b in ("%%a") do echo      http://%%b:19401
)
echo.
node "server\cf-tunnel.mjs"
goto END

:NONODE
echo.
echo  Node.js not found: https://nodejs.org
echo.
pause
exit /b 1

:FAILED
echo.
echo  Failed to build profile, see errors above.
echo.
pause
exit /b 1

:END
pause