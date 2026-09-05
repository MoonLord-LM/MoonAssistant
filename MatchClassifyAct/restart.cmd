@echo off
rem ============================================================
rem  MCA control service : START / RESTART helper
rem
rem  Always rebuilds: stop&clean -> mvn package -> start.
rem  stop&clean stops the running instance and removes target\,
rem  so every restart is a full fresh build.
rem
rem  Usage:
rem     restart.cmd           stop&clean -> mvn package -> start
rem     restart.cmd build     same as above (kept for compatibility)
rem
rem  Runtime logs: log\std.log / log\error.log  (under log\ folder)
rem ============================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "JDK17=C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"

rem ---------- stop the running instance and remove target\ (stop&clean) ----------
call "%~dp0stop&clean.cmd" quiet

rem ---------- build ----------
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

rem ---------- pick a java (17+) ----------
set "JAVA_BIN=%JDK17%\bin\java.exe"
if not exist "%JAVA_BIN%" set "JAVA_BIN=java"
if "%JAVA_BIN%"=="java" (
    java -version >nul 2>nul || ( echo [restart] no usable java found, set JDK17 path in this script. & exit /b 1 )
)

echo [restart] starting service (hidden console, logs in log\std.log and log\error.log) ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$base='%~dp0'; New-Item -ItemType Directory -Force -Path ($base+'log') | Out-Null; Start-Process -FilePath '%JAVA_BIN%' -ArgumentList '-Dfile.encoding=UTF-8','-jar','target\MatchClassifyAct-0.0.1-SNAPSHOT.jar' -WorkingDirectory $base -WindowStyle Hidden -RedirectStandardOutput ($base+'log\std.log') -RedirectStandardError ($base+'log\error.log')"

exit /b 0
