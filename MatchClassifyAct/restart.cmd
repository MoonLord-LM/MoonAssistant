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
rem  Runtime logs: console.log / console.err.log  (project root)
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
if "%JAVA_BIN%"=="java" (
    java -version >nul 2>nul || ( echo [restart] no usable java found, set JDK17 path in this script. & exit /b 1 )
)

echo [restart] starting service (hidden console, logs in console.log) ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$base='%~dp0'; Start-Process -FilePath '%JAVA_BIN%' -ArgumentList '-Dfile.encoding=UTF-8','-jar','target\MatchClassifyAct-0.0.1-SNAPSHOT.jar' -WorkingDirectory $base -WindowStyle Hidden -RedirectStandardOutput ($base+'console.log') -RedirectStandardError ($base+'console.err.log')"

rem ---------- wait until http is ready (max ~25s) ----------
set "READY="
for /l %%i in (1,1,25) do (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/annotate' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { Write-Output 'READY' } } catch { }" 2>nul | findstr /C:"READY" >nul && set "READY=1" && goto :ready
    timeout /t 1 /nobreak >nul 2>nul
)
:ready
if defined READY (
    echo [restart] service is up: http://127.0.0.1:8080/annotate
) else (
    echo [restart] process started but HTTP check not confirmed yet, see console.log.
)
exit /b 0
