@echo off
setlocal

set "APP_HOME=%~dp0"

if not defined JAVA_HOME goto usePath
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto runGradle
echo ERROR: JAVA_HOME does not point to a valid Java installation. 1>&2
endlocal & exit /b 1

:usePath
set "JAVA_EXE=java.exe"
where.exe java.exe >nul 2>&1
if errorlevel 1 goto noJava

:runGradle
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
set "EXIT_CODE=%ERRORLEVEL%"

endlocal & exit /b %EXIT_CODE%

:noJava
echo ERROR: Java 17 is required. Install a JDK and set JAVA_HOME. 1>&2
endlocal & exit /b 1
