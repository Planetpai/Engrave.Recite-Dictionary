#!/bin/bash
# Gradle wrapper startup script for UNIX

export JAVA_HOME="E:/AndroidDev/jdk"
export ANDROID_HOME="E:/AndroidDev/sdk"
export ANDROID_SDK_ROOT="E:/AndroidDev/sdk"
export GRADLE_USER_HOME="E:/AndroidDev/gradle-home"

APP_HOME=$( cd "${APP_HOME:-./}" > /dev/null && pwd -P ) || exit
APP_NAME="Gradle"
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVA_HOME/bin/java" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
