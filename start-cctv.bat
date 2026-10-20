@echo off
setlocal

set "JAR=%~dp0target\cctv-0.0.1-SNAPSHOT.jar"

if not exist "%JAR%" (
    echo CCTV JAR not found:
    echo "%JAR%"
    echo Build it first with mvnw.cmd package.
    pause
    exit /b 1
)

java -jar "%JAR%" %*
