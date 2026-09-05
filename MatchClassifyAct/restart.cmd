@echo off
rem ============================================================
rem  MCA control service : START / RESTART helper
rem
rem  Usage:
rem     restart.cmd           start the service right away
rem                           (rebuild skipped; use existing jar)
rem     restart.cmd build     stop -> mvn package -> start
rem
rem  If no jar exists yet, it auto-builds once even without "build".
rem  The service runs in the foreground of this console window and
rem  streams its log live; close the window or press Ctrl+C to stop.
rem ============================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "JAR=%~dp0target\MatchClassifyAct-0.0.1-SNAPSHOT.jar"
set "JDK17=C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"

rem ---------- decide whether to rebuild ----------
if /i "%~1"=="build" goto :build
if exist "%JAR%" goto :run
echo [restart] jar not found, auto rebuild first.
:build
rem ---------- stop existing instance first (running java locks the jar file, else repackage fails) ----------
call "%~dp0stop.cmd" quiet
if exist "%JDK17%\bin\javac.exe" (
    echo [restart] using JDK17: %JDK17%
    set "JAVA_HOME=%JDK17%"
    set "PATH=%JDK17%\bin;%PATH%"
)
echo [restart] building with maven ...
call mvn -q -DskipTests package
if errorlevel 1 (
    echo [restart] BUILD FAILED, aborted.
    exit /b 1
)
echo [restart] build OK.

:run
rem ---------- stop an existing instance ----------
call "%~dp0stop.cmd" quiet

rem ---------- pick a java (17+) ----------
set "JAVA_BIN=%JDK17%\bin\java.exe"
if not exist "%JAVA_BIN%" set "JAVA_BIN=java"

rem ---------- run in foreground: logs stream live into this window ----------
rem (close this window or press Ctrl+C to stop the service)
title MCA service : close window / Ctrl+C to stop
"%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%JAR%"
