/* windowcap.cpp - WindowsCapture.exe：Windows Graphics Capture (WGC) 单帧采集器
 *
 * 通过 WGC 抓取指定窗口的合成内容（含 GPU/DX 渲染），与窗口是否在前台、
 * 是否被遮挡、屏幕坐标/DPI 缩放全部无关。把抓到的第一帧写成 24-bit BMP。
 *
 * 窗口处理策略：
 *   - 非分层窗口：按窗口直接捕获（CreateForWindow），输出窗口合成内容；
 *   - WS_EX_LAYERED 分层窗口：WGC 无法按窗口捕获（模拟器/悬浮窗常见），
 *     改为抓取窗口所在显示器（CreateForMonitor）再按窗口矩形裁剪输出。
 *
 * 用法（以下 5 种窗口定位方式互斥，一次只提供一种）:
 *   WindowsCapture.exe --hwnd <十进制句柄> --out <输出.bmp> [--timeout-ms <毫秒>]
 *   WindowsCapture.exe --title <完整标题> --out <输出.bmp> [--timeout-ms <毫秒>]
 *   WindowsCapture.exe --search <标题子串> --out <输出.bmp> [--timeout-ms <毫秒>]     // 取第一个匹配窗口
 *   WindowsCapture.exe --pid <进程ID> --out <输出.bmp> [--timeout-ms <毫秒>]           // 取进程主窗口
 *   WindowsCapture.exe --process <进程文件名> --out <输出.bmp> [--timeout-ms <毫秒>]   // 如 MuMuNxDevice.exe（可省 .exe）
 *
 * 退出码（分段编号：万位=大类，低位=具体位置，每类留足空间）:
 *   0 成功
 *
 *   10000+  参数问题：命令行写错，每类一码（Java 固定传参不会触发）
 *       10000 未知参数                   10001 缺少必需参数 --out
 *       10002 未提供窗口定位方式         10003 同时提供多种定位方式（互斥）
 *       10004 参数缺少值（--xxx 后未跟值）10005 --hwnd 不是合法十进制数字
 *       10006 --pid 不是合法数字         10007 --timeout-ms 不是合法数字
 *
 *   20000+  窗口问题：目标窗口的状态不允许 / 无法捕获（用户恢复 / 重试即可）
 *       20000 找不到窗口（无匹配可见窗口 / --hwnd 句柄无效）
 *       20001 窗口最小化 / 无可捕获内容（含捕获项尺寸为 0）
 *       20002 分层窗口完全不在显示器可见范围内
 *       20003 创建捕获项失败（窗口受保护 / 不可捕获）
 *       20004 在超时时间内没有取到帧（窗口未产出画面）
 *       20005 帧内容尺寸为空
 *
 *   30000+  程序异常：采集器内部 / 系统错误与未预期异常（每个失败点一码）
 *       30000 D3D11 设备创建失败         30001 包装 DXGI 设备失败      30002 取回 IDirect3DDevice 失败
 *       30003 取显示器信息失败           30004 取帧 Surface DXGI 接口失败 30005 取 D3D 帧纹理失败
 *       30006 创建 staging 纹理失败      30007 Map 失败               30008 写文件失败
 *       30009 WinRT 异常(hresult_error)  30010 未知异常
 *
 * stdout: 成功时输出 "OK <宽>x<高>"；stdout 之外信息走 stderr。
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellscalingapi.h>
#include <d3d11.h>
#include <dxgi.h>
#include <inspectable.h>
#include <windows.graphics.directx.direct3d11.interop.h>  // CreateDirect3D11DeviceFromDXGIDevice / IDirect3DDxgiInterfaceAccess

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cwctype>
#include <fcntl.h>
#include <functional>
#include <io.h>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "windowsapp.lib")

using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;

namespace ExitCode {
    // 0 成功
    constexpr int OK = 0;

    // 10000+ 参数问题：命令行写错，每类一码（Java 固定传参不会触发）
    constexpr int ARG_UNKNOWN = 10000;         // 未知参数
    constexpr int ARG_OUT_MISSING = 10001;     // 缺少必需参数 --out
    constexpr int ARG_NO_LOCATE = 10002;       // 未提供窗口定位方式
    constexpr int ARG_MULTI_LOCATE = 10003;    // 同时提供多个定位方式（互斥）
    constexpr int ARG_MISSING_VALUE = 10004;   // --xxx 后缺少参数值
    constexpr int ARG_HWND_INVALID = 10005;    // --hwnd 不是合法十进制数字
    constexpr int ARG_PID_INVALID = 10006;     // --pid 不是合法数字
    constexpr int ARG_TIMEOUT_INVALID = 10007; // --timeout-ms 不是合法数字

    // 20000+ 窗口问题：目标窗口的状态不允许 / 无法捕获（用户恢复 / 重试即可）
    constexpr int NO_WINDOW = 20000;           // 找不到窗口：无匹配可见窗口 / --hwnd 句柄无效
    constexpr int MINIMIZED = 20001;           // 窗口最小化 / 无可捕获内容（含捕获项尺寸为 0）
    constexpr int WINDOW_OFFSCREEN = 20002;    // 分层窗口完全不在显示器可见范围内
    constexpr int ITEM_CREATE_FAILED = 20003;  // 创建捕获项失败（受保护/不可捕获）
    constexpr int NO_FRAME = 20004;            // 超时未取到帧（窗口未产出画面）
    constexpr int FRAME_CONTENT_EMPTY = 20005; // 帧内容尺寸为空

    // 30000+ 程序异常：采集器内部/系统错误（每个失败点一码）与未预期异常兜底
    constexpr int DEVICE_CREATE_FAILED = 30000;  // D3D11 设备创建失败（硬件与 WARP 均不可用）
    constexpr int DEVICE_WRAP_FAILED = 30001;    // 包装 DXGI 设备为 WinRT IDirect3DDevice 失败
    constexpr int DEVICE_QI_FAILED = 30002;      // 取回 IDirect3DDevice 失败
    constexpr int MONITOR_INFO_FAILED = 30003;   // 取窗口所在显示器信息失败
    constexpr int DXGI_ACCESS_FAILED = 30004;    // 取帧 Surface 的 DXGI 访问接口失败
    constexpr int TEXTURE_GET_FAILED = 30005;    // 取 D3D 帧纹理失败
    constexpr int STAGING_CREATE_FAILED = 30006; // 创建 staging 纹理失败
    constexpr int MAP_FAILED = 30007;            // staging Map 失败
    constexpr int WRITE_FAILED = 30008;          // 输出文件创建/写入失败
    constexpr int WINRT_EXCEPTION = 30009;       // WinRT 异常（hresult_error，HRESULT 见 stderr）
    constexpr int UNKNOWN_EXCEPTION = 30010;     // 其它未预期异常
}

// 来自 SDK <Windows.Graphics.Capture.Interop.h>，C++/WinRT 下需自行声明
struct DECLSPEC_UUID("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356") IGraphicsCaptureItemInterop : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE CreateForWindow(HWND window, REFIID riid, void** result) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateForMonitor(HMONITOR monitor, REFIID riid, void** result) = 0;
};

static std::wstring toLower(std::wstring s) {
    std::transform(s.begin(), s.end(), s.begin(), [](wchar_t c) { return static_cast<wchar_t>(::towlower(c)); });
    return s;
}

// ---- 窗口查找：EnumWindows 遍历可见顶层窗口 ----
using Pred = std::function<bool(HWND)>;

// largest=true 返回满足条件中面积最大者；否则返回第一个匹配
static HWND findVisibleWindow(const Pred& pred, bool largest) {
    struct Ctx { const Pred* pred; HWND best; ULONGLONG bestArea; bool largest; };
    Ctx ctx{ &pred, nullptr, 0, largest };
    EnumWindows([](HWND h, LPARAM lp) -> BOOL {
        Ctx& c = *reinterpret_cast<Ctx*>(lp);
        if (!IsWindowVisible(h)) return TRUE;
        if (!(*c.pred)(h)) return TRUE;
        if (!c.largest) { c.best = h; return FALSE; }  // 取第一个即停止枚举
        RECT r;
        GetWindowRect(h, &r);
        ULONGLONG area = static_cast<ULONGLONG>(r.right - r.left) * (r.bottom - r.top);
        if (area > c.bestArea) { c.bestArea = area; c.best = h; }
        return TRUE;
    }, reinterpret_cast<LPARAM>(&ctx));
    return ctx.best;
}

static std::wstring windowTitle(HWND h) {
    wchar_t buf[512];
    int n = GetWindowTextW(h, buf, 512);
    return n > 0 ? std::wstring(buf, n) : std::wstring();
}

// --title：完整标题匹配（忽略大小写）
static HWND findWindowByTitle(const std::wstring& title) {
    std::wstring key = toLower(title);
    return findVisibleWindow([&](HWND h) { return toLower(windowTitle(h)) == key; }, false);
}

// --search：标题子串匹配，取第一个命中窗口
static HWND findWindowBySearch(const std::wstring& keyword) {
    std::wstring key = toLower(keyword);
    return findVisibleWindow([&](HWND h) { return toLower(windowTitle(h)).find(key) != std::wstring::npos; }, false);
}

// --pid：进程 ID，取该进程面积最大的可见窗口（主窗口）
static HWND findWindowByPid(DWORD pid) {
    return findVisibleWindow([&](HWND h) {
        DWORD wpid = 0;
        GetWindowThreadProcessId(h, &wpid);
        return wpid == pid;
    }, true);
}

// --process：进程文件名（可省略 .exe、忽略大小写），取面积最大的可见窗口
static HWND findWindowByProcess(const std::wstring& name) {
    std::wstring key = toLower(name);
    return findVisibleWindow([&](HWND h) -> bool {
        DWORD pid = 0;
        GetWindowThreadProcessId(h, &pid);
        if (!pid) return false;
        HANDLE hp = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
        if (!hp) return false;
        wchar_t path[1024];
        DWORD sz = 1024;
        BOOL ok = QueryFullProcessImageNameW(hp, 0, path, &sz);
        CloseHandle(hp);
        if (!ok || sz == 0) return false;
        std::wstring file(std::wstring(path, sz));
        size_t slash = file.find_last_of(L"\\/");
        if (slash != std::wstring::npos) file = file.substr(slash + 1);  // 去掉目录
        std::wstring lf = toLower(file);
        if (lf == key) return true;
        size_t dot = lf.find_last_of(L'.');
        return dot != std::wstring::npos && lf.substr(0, dot) == key;  // 允许不带 .exe
    }, true);
}

enum class LocateMode { None, Hwnd, Title, Search, Pid, Process };

struct Options {
    LocateMode mode = LocateMode::None;  // 窗口定位方式（Hwnd 直接定位，其余按规则查找）
    uintptr_t hwnd = 0;                  // --hwnd 十进制句柄
    std::wstring title;                  // --title 完整标题
    std::wstring search;                 // --search 标题子串
    DWORD pid = 0;                       // --pid 进程 ID
    std::wstring process;                // --process 进程文件名
    std::wstring out;                    // --out 输出 BMP 路径
    int timeoutMs = 3000;                // --timeout-ms 抓帧超时（毫秒）
};

// 解析参数；成功返回 ExitCode::OK，参数错误返回对应 10000+ 细分码
static int parseArgs(int argc, wchar_t** argv, Options& o) {
    int locateCount = 0;
    for (int i = 1; i < argc; ++i) {
        std::wstring a = argv[i];
        auto need = [&](const wchar_t* name) -> std::wstring {
            if (i + 1 < argc) return argv[++i];
            std::wcerr << L"缺少参数值: " << name << L"\n";
            return {};
        };
        if (a == L"--hwnd") {
            std::wstring v = need(L"--hwnd");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            try {
                o.hwnd = std::stoull(v);
                o.mode = LocateMode::Hwnd;
                ++locateCount;
            } catch (...) {
                std::wcerr << L"--hwnd 不是合法数字: " << v << L"\n";
                return ExitCode::ARG_HWND_INVALID;
            }
        } else if (a == L"--title") {
            std::wstring v = need(L"--title");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            o.title = v;
            o.mode = LocateMode::Title;
            ++locateCount;
        } else if (a == L"--search") {
            std::wstring v = need(L"--search");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            o.search = v;
            o.mode = LocateMode::Search;
            ++locateCount;
        } else if (a == L"--pid") {
            std::wstring v = need(L"--pid");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            try {
                o.pid = static_cast<DWORD>(std::stoul(v));
                o.mode = LocateMode::Pid;
                ++locateCount;
            } catch (...) {
                std::wcerr << L"--pid 不是合法进程 ID: " << v << L"\n";
                return ExitCode::ARG_PID_INVALID;
            }
        } else if (a == L"--process") {
            std::wstring v = need(L"--process");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            o.process = v;
            o.mode = LocateMode::Process;
            ++locateCount;
        } else if (a == L"--out") {
            std::wstring v = need(L"--out");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            o.out = v;
        } else if (a == L"--timeout-ms") {
            std::wstring v = need(L"--timeout-ms");
            if (v.empty()) return ExitCode::ARG_MISSING_VALUE;
            try {
                o.timeoutMs = std::stoi(v);
                if (o.timeoutMs < 100) o.timeoutMs = 100;
            } catch (...) {
                std::wcerr << L"--timeout-ms 不是合法数字: " << v << L"\n";
                return ExitCode::ARG_TIMEOUT_INVALID;
            }
        } else {
            std::wcerr << L"未知参数: " << a << L"\n";
            return ExitCode::ARG_UNKNOWN;
        }
    }

    if (o.out.empty()) { std::wcerr << L"缺少 --out 参数\n"; return ExitCode::ARG_OUT_MISSING; }
    if (locateCount == 0) { std::wcerr << L"必须提供 --hwnd / --title / --search / --pid / --process 之一\n"; return ExitCode::ARG_NO_LOCATE; }
    if (locateCount > 1) { std::wcerr << L"窗口定位方式（--hwnd/--title/--search/--pid/--process）只能提供一种\n"; return ExitCode::ARG_MULTI_LOCATE; }
    return ExitCode::OK;
}

// 将 BGRA8 像素写为 24-bit BMP（自底向上，行按 4 字节对齐）
static int writeBmp24(const std::wstring& path, const uint8_t* src, uint32_t srcRowPitch, uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) return ExitCode::WRITE_FAILED;

    uint32_t rowBytes = width * 3;
    uint32_t padded = (rowBytes + 3u) & ~3u;
    uint32_t dataSize = padded * height;
    uint32_t headerSize = 14 + 40;
    std::vector<uint8_t> bmp(headerSize + dataSize, 0);

    uint8_t* p = bmp.data();
    // 文件头（BITMAPFILEHEADER）：'BM' + 文件大小 + 像素数据偏移
    p[0] = 'B'; p[1] = 'M';
    p[2] = static_cast<uint8_t>(bmp.size() & 0xFF);
    p[3] = static_cast<uint8_t>((bmp.size() >> 8) & 0xFF);
    p[4] = static_cast<uint8_t>((bmp.size() >> 16) & 0xFF);
    p[5] = static_cast<uint8_t>((bmp.size() >> 24) & 0xFF);
    p[10] = headerSize;
    // 信息头（BITMAPINFOHEADER）：40 字节；24-bit，无压缩
    p[14] = 40;
    p[18] = static_cast<uint8_t>(width & 0xFF);
    p[19] = static_cast<uint8_t>((width >> 8) & 0xFF);
    p[20] = static_cast<uint8_t>((width >> 16) & 0xFF);
    p[21] = static_cast<uint8_t>((width >> 24) & 0xFF);
    p[22] = static_cast<uint8_t>(height & 0xFF);
    p[23] = static_cast<uint8_t>((height >> 8) & 0xFF);
    p[24] = static_cast<uint8_t>((height >> 16) & 0xFF);
    p[25] = static_cast<uint8_t>((height >> 24) & 0xFF);
    p[26] = 1;    // planes
    p[28] = 24;   // bitcount

    // 自底向上逐行拷贝并去掉 alpha
    uint8_t* dst = p + headerSize;
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* srow = src + static_cast<size_t>(height - 1 - y) * srcRowPitch;
        uint8_t* drow = dst + static_cast<size_t>(y) * padded;
        for (uint32_t x = 0; x < width; ++x) {
            drow[x * 3 + 0] = srow[x * 4 + 0];  // B
            drow[x * 3 + 1] = srow[x * 4 + 1];  // G
            drow[x * 3 + 2] = srow[x * 4 + 2];  // R
        }
    }

    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        std::wcerr << L"无法创建输出文件: " << path << L" (err=" << GetLastError() << L")\n";
        return ExitCode::WRITE_FAILED;
    }
    DWORD written = 0;
    BOOL ok = WriteFile(file, bmp.data(), static_cast<DWORD>(bmp.size()), &written, nullptr);
    CloseHandle(file);
    if (!ok || written != bmp.size()) {
        std::wcerr << L"写入文件失败\n";
        return ExitCode::WRITE_FAILED;
    }
    return ExitCode::OK;
}

int wmain(int argc, wchar_t** argv) {
    // 管道重定向下宽字符流需显式设为 UTF-8 才会输出
    _setmode(_fileno(stdout), _O_U8TEXT);
    _setmode(_fileno(stderr), _O_U8TEXT);

    // Per-Monitor DPI 感知：保证窗口/显示器坐标取到的是物理像素
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    Options o;
    if (int rc = parseArgs(argc, argv, o); rc != ExitCode::OK) {
        std::wcerr << L"用法: WindowsCapture.exe <定位方式> --out <输出.bmp> [--timeout-ms <毫秒>]\n"
                      L"  定位方式: --hwnd <句柄> | --title <完整标题> | --search <子串> | --pid <进程ID> | --process <进程名>\n";
        return rc;
    }

    HWND hwnd = nullptr;
    switch (o.mode) {
        case LocateMode::Hwnd:    hwnd = reinterpret_cast<HWND>(o.hwnd); break;
        case LocateMode::Title:   hwnd = findWindowByTitle(o.title); break;
        case LocateMode::Search:  hwnd = findWindowBySearch(o.search); break;
        case LocateMode::Pid:     hwnd = findWindowByPid(o.pid); break;
        case LocateMode::Process: hwnd = findWindowByProcess(o.process); break;
        default: break;
    }
    if (o.mode != LocateMode::Hwnd) {
        if (!hwnd) {
            std::wcerr << L"没有找到匹配的可见窗口\n";
            return ExitCode::NO_WINDOW;
        }
        std::wcerr << L"定位到窗口: [" << windowTitle(hwnd) << L"] hwnd=" << reinterpret_cast<uintptr_t>(hwnd) << L"\n";
    }

    if (!IsWindow(hwnd)) {
        std::wcerr << L"句柄不是有效窗口\n";
        return ExitCode::NO_WINDOW;
    }
    if (IsIconic(hwnd)) {
        std::wcerr << L"窗口已最小化，请先还原窗口\n";
        return ExitCode::MINIMIZED;
    }

    try {
        winrt::init_apartment();

        // 1. 创建 D3D11 设备（WGC 要求 BGRA 支持），硬件不可用时退回 WARP
        winrt::com_ptr<ID3D11Device> d3dDevice;
        winrt::com_ptr<ID3D11DeviceContext> d3dContext;
        {
            D3D_FEATURE_LEVEL levels[] = { D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0 };
            D3D_FEATURE_LEVEL got{};
            HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                           levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, d3dDevice.put(), &got, d3dContext.put());
            if (FAILED(hr)) {
                hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                       levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, d3dDevice.put(), &got, d3dContext.put());
            }
            if (FAILED(hr)) {
                std::wcerr << L"创建 D3D11 设备失败: 0x" << std::hex << hr << std::dec << L"\n";
                return ExitCode::DEVICE_CREATE_FAILED;
            }
        }

        // 2. 包装成 WinRT 的 IDirect3DDevice
        IDirect3DDevice winrtDevice{ nullptr };
        {
            winrt::com_ptr<IDXGIDevice> dxgiDevice = d3dDevice.as<IDXGIDevice>();
            ::IInspectable* raw = nullptr;
            HRESULT hr = CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), &raw);
            if (FAILED(hr) || !raw) {
                std::wcerr << L"包装 D3D 设备失败: 0x" << std::hex << hr << std::dec << L"\n";
                return ExitCode::DEVICE_WRAP_FAILED;
            }
            hr = raw->QueryInterface(winrt::guid_of<IDirect3DDevice>(), winrt::put_abi(winrtDevice));
            raw->Release();
            if (FAILED(hr)) {
                std::wcerr << L"获取 IDirect3DDevice 失败: 0x" << std::hex << hr << std::dec << L"\n";
                return ExitCode::DEVICE_QI_FAILED;
            }
        }

        // 3. 创建捕获项；分层窗口改抓所在显示器并记录裁剪区域
        bool layered = (GetWindowLongW(hwnd, GWL_EXSTYLE) & WS_EX_LAYERED) != 0;
        int cropOffsetX = 0;  // 裁剪偏移（相对显示器左上角）
        int cropOffsetY = 0;
        int cropW = 0;        // 裁剪宽高
        int cropH = 0;

        auto factory = winrt::get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        GraphicsCaptureItem item{ nullptr };
        HRESULT hr;
        if (layered) {
            HMONITOR mon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi{ sizeof(MONITORINFO) };
            if (!GetMonitorInfoW(mon, &mi)) {
                std::wcerr << L"无法获取窗口所在显示器信息\n";
                return ExitCode::MONITOR_INFO_FAILED;
            }
            RECT wr;
            GetWindowRect(hwnd, &wr);
            // 只取窗口落在该显示器内的区域（窗口可能跨屏）
            int left = (std::max)(wr.left, mi.rcMonitor.left);
            int top = (std::max)(wr.top, mi.rcMonitor.top);
            int right = (std::min)(wr.right, mi.rcMonitor.right);
            int bottom = (std::min)(wr.bottom, mi.rcMonitor.bottom);
            if (right <= left || bottom <= top) {
                std::wcerr << L"窗口不在显示器可见范围内\n";
                return ExitCode::WINDOW_OFFSCREEN;
            }
            cropOffsetX = left - mi.rcMonitor.left;
            cropOffsetY = top - mi.rcMonitor.top;
            cropW = right - left;
            cropH = bottom - top;
            hr = factory->CreateForMonitor(mon, winrt::guid_of<GraphicsCaptureItem>(), winrt::put_abi(item));
            std::wcerr << L"分层窗口 -> 按显示器捕获，裁剪区域 " << cropOffsetX << L"," << cropOffsetY << L" " << cropW << L"x" << cropH << L"\n";
        } else {
            hr = factory->CreateForWindow(hwnd, winrt::guid_of<GraphicsCaptureItem>(), winrt::put_abi(item));
        }
        if (FAILED(hr)) {
            std::wcerr << L"创建捕获项失败（可能窗口受保护或不可捕获）: 0x" << std::hex << hr << std::dec << L"\n";
            return ExitCode::ITEM_CREATE_FAILED;
        }

        auto itemSize = item.Size();
        if (itemSize.Width <= 0 || itemSize.Height <= 0) {
            std::wcerr << L"窗口无可见内容（最小化/隐藏?）\n";
            return ExitCode::MINIMIZED;
        }

        // 4. 创建帧池与采集会话并开始捕获
        Direct3D11CaptureFramePool pool = Direct3D11CaptureFramePool::Create(winrtDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, itemSize);
        GraphicsCaptureSession session = pool.CreateCaptureSession(item);
        session.StartCapture();

        // 5. 轮询取第一帧，超时返回
        Direct3D11CaptureFrame frame{ nullptr };
        {
            auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(o.timeoutMs);
            while (std::chrono::steady_clock::now() < deadline) {
                frame = pool.TryGetNextFrame();
                if (frame) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
            }
        }
        if (!frame) {
            std::wcerr << L"超时未取到帧\n";
            return ExitCode::NO_FRAME;
        }

        // 6. 取帧纹理并创建 CPU 可读的 staging 纹理
        auto contentSize = frame.ContentSize();
        if (contentSize.Width <= 0 || contentSize.Height <= 0) {
            std::wcerr << L"帧内容尺寸为空\n";
            return ExitCode::FRAME_CONTENT_EMPTY;
        }

        using DxgiAccess = ::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess;
        winrt::com_ptr<DxgiAccess> access;
        hr = reinterpret_cast<IUnknown*>(winrt::get_abi(frame.Surface()))->QueryInterface(winrt::guid_of<DxgiAccess>(), winrt::put_abi(access));
        if (FAILED(hr)) {
            std::wcerr << L"获取 DXGI 接口失败: 0x" << std::hex << hr << std::dec << L"\n";
            return ExitCode::DXGI_ACCESS_FAILED;
        }
        winrt::com_ptr<ID3D11Texture2D> frameTexture;
        hr = access->GetInterface(__uuidof(ID3D11Texture2D), frameTexture.put_void());
        if (FAILED(hr)) {
            std::wcerr << L"获取 D3D 纹理失败: 0x" << std::hex << hr << std::dec << L"\n";
            return ExitCode::TEXTURE_GET_FAILED;
        }

        D3D11_TEXTURE2D_DESC desc;
        frameTexture->GetDesc(&desc);

        D3D11_TEXTURE2D_DESC stagingDesc = desc;
        stagingDesc.BindFlags = 0;
        stagingDesc.Usage = D3D11_USAGE_STAGING;
        stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        stagingDesc.MiscFlags = 0;
        winrt::com_ptr<ID3D11Texture2D> staging;
        hr = d3dDevice->CreateTexture2D(&stagingDesc, nullptr, staging.put());
        if (FAILED(hr)) {
            std::wcerr << L"创建 staging 纹理失败: 0x" << std::hex << hr << std::dec << L"\n";
            return ExitCode::STAGING_CREATE_FAILED;
        }

        // 输出尺寸与拷贝区域：普通模式取帧内容；分层模式取显示器上窗口所在区域
        uint32_t outW = 0;
        uint32_t outH = 0;
        D3D11_BOX box{};
        if (layered) {
            outW = static_cast<uint32_t>(cropW);
            outH = static_cast<uint32_t>(cropH);
            box = { static_cast<UINT>(cropOffsetX), static_cast<UINT>(cropOffsetY), 0,
                    static_cast<UINT>(cropOffsetX + cropW), static_cast<UINT>(cropOffsetY + cropH), 1 };
        } else {
            outW = std::min<uint32_t>(static_cast<uint32_t>(contentSize.Width), desc.Width);
            outH = std::min<uint32_t>(static_cast<uint32_t>(contentSize.Height), desc.Height);
            box = { 0, 0, 0, outW, outH, 1 };
        }
        d3dContext->CopySubresourceRegion(staging.get(), 0, 0, 0, 0, frameTexture.get(), 0, &box);

        D3D11_MAPPED_SUBRESOURCE mapped{};
        hr = d3dContext->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped);
        if (FAILED(hr)) {
            std::wcerr << L"Map 失败: 0x" << std::hex << hr << std::dec << L"\n";
            return ExitCode::MAP_FAILED;
        }

        // CopySubresourceRegion 已把裁剪区域拷到 staging 左上角 (0,0)，直接从这里读即可
        const uint8_t* src = static_cast<const uint8_t*>(mapped.pData);
        int code = writeBmp24(o.out, src, mapped.RowPitch, outW, outH);
        d3dContext->Unmap(staging.get(), 0);

        if (code == ExitCode::OK) {
            std::wcout << L"OK " << outW << L"x" << outH << L"\n";
        }
        return code;
    }
    catch (winrt::hresult_error const& e) {
        std::wcerr << L"捕获异常: 0x" << std::hex << static_cast<uint32_t>(e.code()) << std::dec << L" " << winrt::to_string(e.message()).c_str() << L"\n";
        return ExitCode::WINRT_EXCEPTION;
    }
    catch (...) {
        std::wcerr << L"未知异常\n";
        return ExitCode::UNKNOWN_EXCEPTION;
    }
}
