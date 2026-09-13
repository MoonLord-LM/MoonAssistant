@echo off
chcp 65001
rem ============================================================
rem  MCA control service : STOP + CLEAN helper
rem  Ends the java process running MatchClassifyAct jar, then
rem  removes the target\ build output (forces a full rebuild
rem  next time, e.g. "restart.cmd build").
rem
rem  Usage:
rem     stop&clean.cmd           normal (prints a message)
rem     stop&clean.cmd quiet     no extra banner (used by restart.cmd)
rem ============================================================
setlocal

set "_ELEV_ARGS="
if not "%~1"=="" set "_ELEV_ARGS=-ArgumentList '%~1' "

whoami /groups 2>nul | findstr /c:"S-1-16-12288" >nul 2>&1
if errorlevel 1 (
    echo [stop] 需要管理员权限
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' %_ELEV_ARGS%-WorkingDirectory '%~dp0' -Verb RunAs"
    exit /b 0
)

if not "%~1"=="quiet" echo [stop] stopping MCA control service ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*MatchClassifyAct-0.0.1-SNAPSHOT.jar*' }; if (-not $p) { Write-Output '[stop] no running instance found.' } else { $p | ForEach-Object { Write-Output ('[stop] killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force } }"
rd /s /q "target"
echo [clean] removed target\ (build output).
endlocal
