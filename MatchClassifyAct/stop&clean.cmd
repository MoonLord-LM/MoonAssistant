@echo off
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
if not "%~1"=="quiet" echo [stop] stopping MCA control service ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*MatchClassifyAct-0.0.1-SNAPSHOT.jar*' }; if (-not $p) { Write-Output '[stop] no running instance found.' } else { $p | ForEach-Object { Write-Output ('[stop] killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force } }"
rd /s /q "target"
echo [clean] removed target\ (build output).
endlocal
