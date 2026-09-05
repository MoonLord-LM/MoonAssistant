@echo off
rem ============================================================
rem  MCA control service : STOP + CLEAN helper
rem  Ends the java process running MatchClassifyAct jar, then
rem  removes the target\ build output (forces a full rebuild
rem  next time, e.g. "restart.cmd build").
rem ============================================================
setlocal
call "%~dp0stop.cmd"
rd /s /q "target"
echo [clean] removed target\ (build output).
endlocal
