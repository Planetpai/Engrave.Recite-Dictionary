@rem Gradle startup script for Windows
@rem
@setlocal

set JAVA_HOME=E:\AndroidDev\jdk
set ANDROID_HOME=E:\AndroidDev\sdk
set ANDROID_SDK_ROOT=E:\AndroidDev\sdk
set GRADLE_USER_HOME=E:\AndroidDev\gradle-home

set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_HOME%\bin\java.exe" ^
  -Xmx64m ^
  -Xms64m ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*
