@echo off
setlocal

set AVD_CFG=E:\AndroidDev\android-home\avd\Watch4.avd\config.ini

if not exist "%AVD_CFG%" (
    echo [FAIL] %AVD_CFG% not found. Run create-avd.bat first.
    pause
    exit /b 1
)

copy /y "%AVD_CFG%" "%AVD_CFG%.bak" >nul
echo Backup saved: %AVD_CFG%.bak
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-avd-config.ps1"

echo.
pause
