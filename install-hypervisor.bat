@echo off
setlocal

echo ========================================
echo  Install Android Emulator Hypervisor
echo  Driver (AEHD) - for Wear OS emulator
echo ========================================
echo.
echo  A UAC prompt WILL appear.
echo  Click YES, then REBOOT when it finishes.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-hypervisor.ps1"

echo.
pause
