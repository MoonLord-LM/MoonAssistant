@echo off
rem 一键编译 WindowsCapture.exe（需要 VS BuildTools C++ 工具集 + Windows 11 SDK）
setlocal

set VSROOT=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools
set SDKROOT=C:\Program Files (x86)\Windows Kits\10

if not exist "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" (
    echo 找不到 vcvars64.bat，请确认已安装 VS BuildTools 的 C++ 工具集
    exit /b 1
)

call "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 ( echo vcvars64 初始化失败 & exit /b 1 )

rem 用 SDK 目录下最高的 include 版本（cppwinrt 头所在）
set SDKVER=
for /f "delims=" %%d in ('dir /b /ad /o-n "%SDKROOT%\Include\10.0.*" 2^>nul') do (
    if not defined SDKVER set SDKVER=%%d
)
if not defined SDKVER ( echo 找不到 Windows SDK & exit /b 1 )
echo 使用 Windows SDK: %SDKVER%

cl /nologo /std:c++20 /utf-8 /EHsc /O2 /MT /DUNICODE /D_UNICODE ^
   /I "%SDKROOT%\Include\%SDKVER%\cppwinrt" ^
   windowcap.cpp /Fe:WindowsCapture.exe ^
   /link /SUBSYSTEM:CONSOLE user32.lib d3d11.lib dxgi.lib windowsapp.lib
if errorlevel 1 ( echo 编译失败 & exit /b 1 )

echo 编译成功: WindowsCapture.exe
