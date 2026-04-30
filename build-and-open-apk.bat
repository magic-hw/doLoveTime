@echo off
setlocal
cd /d "%~dp0"

echo [1/2] Building debug APK...
powershell -ExecutionPolicy Bypass -File "%~dp0scripts\build-apk.ps1"
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

set "APK_DIR=%~dp0app\build\outputs\apk\debug"
set "APK_FILE=%APK_DIR%\app-debug.apk"

echo [2/2] Done.
if exist "%APK_FILE%" (
  echo APK: %APK_FILE%
  explorer "%APK_DIR%"
) else (
  echo APK not found: %APK_FILE%
)

pause
exit /b 0
