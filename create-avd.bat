@echo off
setlocal

set JAVA_HOME=E:\AndroidDev\jdk
set ANDROID_HOME=E:\AndroidDev\sdk
set ANDROID_SDK_ROOT=E:\AndroidDev\sdk
set ANDROID_AVD_HOME=E:\AndroidDev\android-home\avd

set AM=%ANDROID_HOME%\cmdline-tools\latest\bin\avdmanager.bat
set SYSIMG=system-images;android-33;android-wear;x86_64
set DEVICE=wearos_large_round

echo ========================================
echo  Create Galaxy Watch4 AVD (Wear OS 4)
echo ========================================
echo  System image : %SYSIMG%
echo  Device       : %DEVICE%  (450x450 round, Watch4 44mm)
echo  AVD home     : %ANDROID_AVD_HOME%
echo.

echo no | call "%AM%" create avd -n Watch4 -k "%SYSIMG%" -d "%DEVICE%" --force

echo.
if exist "%ANDROID_AVD_HOME%\Watch4.avd\config.ini" (
    echo  [OK] AVD created: %ANDROID_AVD_HOME%\Watch4.avd
    echo.
    echo  Now run: patch-avd-config.bat
) else (
    echo  [FAIL] AVD was not created
)

echo.
echo  For Watch4 40mm (396x396) set DEVICE=wearos_small_round above.
echo.
pause
