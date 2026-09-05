@echo off
setlocal

set JAVA_HOME=E:\AndroidDev\jdk
set ANDROID_HOME=E:\AndroidDev\sdk
set ANDROID_AVD_HOME=E:\AndroidDev\android-home\avd
set GRADLE_USER_HOME=E:\AndroidDev\gradle-home

echo ========================================
echo  Wear OS Dev Environment Check
echo ========================================
echo.

echo [1/7] JDK 17
if exist "%JAVA_HOME%\bin\java.exe" (
    echo       OK   %JAVA_HOME%
    "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "version"
) else (
    echo       FAIL %JAVA_HOME%\bin\java.exe not found
)
echo.

echo [2/7] Android SDK
if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    echo       OK   %ANDROID_HOME%
) else (
    echo       FAIL adb.exe not found
)
echo.

echo [3/7] Wear OS 4 system image
if exist "%ANDROID_HOME%\system-images\android-33\android-wear\x86_64\ramdisk.img" (
    echo       OK   android-33 android-wear x86_64
) else (
    echo       FAIL Wear OS 4 system image missing
)
echo.

echo [4/7] AVD Watch4
if exist "%ANDROID_AVD_HOME%\Watch4.avd\config.ini" (
    echo       OK   %ANDROID_AVD_HOME%\Watch4.avd
    findstr /R "^hw.rotaryInput ^hw.gpu.enabled ^hw.lcd.width" "%ANDROID_AVD_HOME%\Watch4.avd\config.ini"
) else (
    echo       FAIL AVD not created - run create-avd.bat
)
echo.

echo [5/7] Emulator hardware acceleration
sc query aehd >nul 2>&1
if %errorlevel%==0 (
    echo       OK   AEHD driver service installed
    "%ANDROID_HOME%\emulator\emulator.exe" -accel-check 2>&1 | findstr /i "accel install" 
) else (
    echo       WARN AEHD not installed - emulator will be very slow
    echo            Run install-hypervisor.bat as admin, then REBOOT
)
echo.

echo [6/7] Project files
if exist "%~dp0app\build.gradle" (
    echo       OK   %~dp0
) else (
    echo       FAIL app\build.gradle not found
)
echo.

echo [7/7] C drive is not being used
echo       Gradle home : %GRADLE_USER_HOME%
echo       AVD home    : %ANDROID_AVD_HOME%
powershell -NoProfile -Command "'C','E' | ForEach-Object { $d=Get-PSDrive $_ -ErrorAction SilentlyContinue; if($d){ Write-Host ('       ' + $_ + ': free ' + [math]::Round($d.Free/1GB,1) + ' GB') } }"
echo.
echo ========================================
echo  Build : gradlew.bat assembleDebug
echo  Run   : start-emulator.bat
echo ========================================
echo.
pause
