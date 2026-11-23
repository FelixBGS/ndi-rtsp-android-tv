@echo off
rem Open inbound TCP 8554 (RTSP), TCP 1935 (RTMP) and UDP 8555 (discovery)
rem on every Windows Firewall profile. Re-launches itself elevated.
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Requesting administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

call :add "NDI-RTSP RTSP 8554" TCP 8554
call :add "NDI-RTSP RTMP 1935" TCP 1935
call :add "NDI-RTSP discovery 8555" UDP 8555

echo.
echo Allowed inbound TCP 8554, TCP 1935 and UDP 8555 on all profiles.
echo.
pause
exit /b 0

:add
netsh advfirewall firewall delete rule name="%~1" >nul 2>&1
netsh advfirewall firewall add rule name="%~1" dir=in action=allow protocol=%2 localport=%3 profile=any
if errorlevel 1 (
  echo [!] Failed to add rule %~1
  exit /b 1
)
echo OK %~1
exit /b 0
