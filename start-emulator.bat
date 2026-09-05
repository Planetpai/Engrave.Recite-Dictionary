@echo off
setlocal

set JAVA_HOME=E:\AndroidDev\jdk
set ANDROID_HOME=E:\AndroidDev\sdk
set ANDROID_SDK_ROOT=E:\AndroidDev\sdk
set ANDROID_AVD_HOME=E:\AndroidDev\android-home\avd
set GRADLE_USER_HOME=E:\AndroidDev\gradle-home

echo ========================================
echo  Wear OS 4 Emulator - Galaxy Watch4
echo ========================================
echo  AVD home : %ANDROID_AVD_HOME%
echo  Screen   : 450x450 round (Watch4 44mm)
echo  Bezel    : rotary input enabled
echo.
echo  Close the emulator window to stop it.
echo.
echo  If the screen stays black, rerun as:
echo    start-emulator.bat swiftshader_indirect
echo.

set GPUMODE=%1
if "%GPUMODE%"=="" set GPUMODE=auto

"%ANDROID_HOME%\emulator\emulator.exe" -avd Watch4 -no-snapshot-load -gpu %GPUMODE%
