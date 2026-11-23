@echo off
rem One-click test server for Windows: MediaMTX + discovery + test stream.
rem Put mediamtx.exe (from https://github.com/bluenviron/mediamtx/releases, *_windows_amd64.zip)
rem next to this file. Python and FFmpeg are optional:
rem   winget install Python.Python.3.12
rem   winget install Gyan.FFmpeg
cd /d "%~dp0"

if not exist mediamtx.exe (
  echo [!] mediamtx.exe not found in %~dp0
  echo     Download mediamtx_vX.Y.Z_windows_amd64.zip from
  echo     https://github.com/bluenviron/mediamtx/releases and unpack mediamtx.exe here.
  pause
  exit /b 1
)

echo Starting MediaMTX (RTSP :8554, RTMP :1935)...
start "MediaMTX" mediamtx.exe mediamtx.yml
timeout /t 2 /nobreak >nul

set PY=
where py >nul 2>nul && set PY=py
if not defined PY where python >nul 2>nul && set PY=python
if defined PY (
  echo Starting discovery service ^(UDP :8555^)...
  start "Discovery" %PY% discovery.py --name "%COMPUTERNAME%"
) else (
  echo [!] Python not found: TVs/phones will not find this PC automatically.
  echo     Enter the address manually in the app, or: winget install Python.Python.3.12
)

where ffmpeg >nul 2>nul
if %errorlevel%==0 (
  echo Starting test stream rtsp://127.0.0.1:8554/live ...
  start "Test stream" ffmpeg -hide_banner -loglevel warning -re ^
    -f lavfi -i testsrc=size=1280x720:rate=30 ^
    -f lavfi -i sine=frequency=440:sample_rate=48000 ^
    -c:v libx264 -preset veryfast -tune zerolatency -profile:v main -pix_fmt yuv420p ^
    -g 30 -keyint_min 30 -bf 0 -b:v 4M -maxrate 4M -bufsize 4M ^
    -c:a aac -b:a 128k ^
    -f rtsp -rtsp_transport tcp -pkt_size 1200 rtsp://127.0.0.1:8554/live
) else (
  echo [!] FFmpeg not found. Publish from OBS instead: Settings - Stream - Custom,
  echo     server rtmp://127.0.0.1:1935, stream key live. Or: winget install Gyan.FFmpeg
)

echo.
echo Addresses of this PC (use the one from your Wi-Fi network):
ipconfig | findstr /c:"IPv4"
echo.
echo Stream URL for manual entry: rtsp://^<IP above^>:8554/live
echo If Windows asks to allow network access for mediamtx / python - click Allow.
echo Close the opened windows to stop.
pause
