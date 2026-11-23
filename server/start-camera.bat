@echo off
rem Запуск сервера MediaMTX, службы автопоиска и захвата веб-камеры через FFmpeg.
cd /d "%~dp0"

set "CAMERA_NAME=Integrated Camera"
if not "%~1"=="" set "CAMERA_NAME=%~1"

if not exist mediamtx.exe (
  echo [!] Ошибка: mediamtx.exe не найден в папке %~dp0
  pause
  exit /b 1
)

where ffmpeg >nul 2>nul
if %errorlevel% neq 0 (
  echo [!] Ошибка: FFmpeg не найден в PATH.
  echo     Установите его командой: winget install Gyan.FFmpeg
  pause
  exit /b 1
)

echo [1/3] Запуск MediaMTX (RTSP :8554, RTMP :1935)...
start "MediaMTX" mediamtx.exe mediamtx.yml
timeout /t 1 /nobreak >nul

echo [2/3] Запуск службы автообнаружения (UDP :8555)...
set PY=
where py >nul 2>nul && set PY=py
if not defined PY where python >nul 2>nul && set PY=python
if defined PY (
  start "Discovery" %PY% discovery.py --name "Webcam (%COMPUTERNAME%)"
) else (
  echo [!] Внимание: Python не найден. Автопоиск отключен, вводите IP вручную.
)

echo [3/3] Запуск захвата камеры "%CAMERA_NAME%"...
echo     (Если у вас другая камера, запустите: start-camera.bat "Имя_Камеры")
echo     (Список устройств: ffmpeg -list_devices true -f dshow -i dummy)

start "Camera Stream" ffmpeg -hide_banner -loglevel warning ^
  -f dshow -framerate 30 -video_size 1280x720 -i video="%CAMERA_NAME%" ^
  -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p ^
  -g 30 -keyint_min 30 -bf 0 -b:v 4M -maxrate 4M -bufsize 2M ^
  -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/live

echo.
echo ========================================================
echo Трансляция камеры запущена: rtsp://127.0.0.1:8554/live
echo Локальные IP-адреса этого ПК:
ipconfig | findstr /c:"IPv4"
echo.
echo Для остановки закройте открывшиеся окна консоли.
echo ========================================================
pause
