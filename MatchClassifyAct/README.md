# MatchClassifyAct (MCA)

> **Match the pixels, Classify the state, Act accordingly.**
> 匹配像素 → 分类标注 → 执行动作。

Spring Boot 3.2.5 + JDK 17 的 Windows **图形程序自动化**工程——面向任意 GUI 程序窗口做「按窗口截图 → 人工标注画面状态 → 按状态驱动鼠标操作」（默认演示目标是 MuMu 模拟器内的安卓程序）。核心链路：

```
截图（画面采集）
 ↓
Match     —— 从画面中按规则找到目标内容（像素/模板匹配，后续可升级 ONNX）
 ↓
Classify  —— 判定画面属于哪个分类标注（登录页 / 主界面 / 弹窗 / 无响应……）
 ↓
Act       —— 根据分类结果执行鼠标移动 / 点击（JNA SendInput）
```

> 当前状态：已完成「后台画面采集（WGC）→ 控制台人工标注 → 执行模式（实时识别 + 一键执行点击）」三段主链路。
> 控制台左上角「工作模式」下拉在 **标注模式 / 执行模式** 间切换，执行模式即 Act 阶段的运行端（见 §2.7）。

---

## 一、目标与需求

面向 MuMu 模拟器等图形程序窗口的自动化截图（任意 GUI 程序画面皆为目标），要求全部满足以下四点：

| # | 需求 | 含义 |
|---|------|------|
| 1 | **后台运行** | 不要求目标窗口在前台 / 最上层 / 未被遮挡；截图时不抢占用户操作 |
| 2 | **支持 DX/GPU 渲染** | 能抓到用 DX/OpenGL/Vulkan 渲染的内容（模拟器或其它 GPU 渲染的图形程序），不出现全黑 |
| 3 | **多显示器缩放精确** | 不同显示器不同缩放比例下坐标不偏移（历史症状：截图"往右下方偏"） |
| 4 | **内容可用于匹配** | 输出与屏幕物理像素一一对应，可直接喂给后续像素/模板匹配 |

非目标：不做录屏 / 录像，只要高质量单帧。不做通用"桌面截图"，只抓指定窗口。

---

## 二、技术方案：Windows Graphics Capture (WGC)

抓「窗口自身合成内容（含 GPU/DX 表面），且与遮挡、前台、显示器缩放都无关」的官方 API
只有 **Windows Graphics Capture**（WinRT/COM 组件，不在传统 `user32/gdi32` C API 里）。
历史方案均不满足 §一 需求：`java.awt.Robot` 整屏截取要求窗口可见在屏、会截到遮挡层、多缩放下坐标
"右下偏移"；`PrintWindow` 对 MuMu 这类 GPU 分层窗口实测全黑。因此本工程用 WGC 自建采集链路。

### 2.1 现成方案调研（官方 & 开源）

针对"**后台 + DX 合成 + 任意缩放无偏移地抓指定窗口**"，2026-09 调研结论：

| 来源 | 方案 | 与本项目契合度 |
|------|------|--------------|
| **微软官方（推荐，本工程采用）** | `Windows.Graphics.Capture`（WGC）。Win10 1809+（最低支持）/ Win11 内置，PowerToys、Game Bar、新版截图工具底层都是它。官方示例：`microsoft/Windows-classic-samples`（ScreenCapture / Win32CaptureSample）、`microsoft/Windows-universal-samples` | ⭐ 唯一官方"按窗口抓合成内容"API，行为最可控 |
| **OBS Studio（开源）** | `libobs` 的窗口/显示器采集：新版走 WGC（`winrt-capture`），另有旧式 `win-capture`（DLL 注入 + 共享纹理，可抓前台独占全屏） | 最成熟的开源**参考实现**，可抄代码；但整体太重（需要注入/多进程/GPU 共享），不适合 Java 单体集成 |
| **.NET / C# 生态** | C# 直接调 WGC 非常顺手（有微软官方 NuGet 投影 + `Windows.Graphics.Capture` 示例）；`ScreenCapture.NET` 等库亦基于 WGC / DXGI | 生态成熟，但本工程是 Java |
| **Python 生态** | `dxcam`（DXGI Desktop Duplication，抓显示器快）、`wgc_python`（WGC 抓窗口）、`mss`（前台抓屏） | 后台抓窗口可用但社区库维护一般，且非 Java |
| **通用命令行截图** | `ffmpeg -f gdigrab`、ShareX、`nircmd` 等 | ❌ 本质仍是桌面/BitBlt，抓不到窗口 GPU 合成内容或无法后台、缩放仍错位 |
| **Java 生态** | 无成熟 WGC 绑定（WinRT COM 无现成 JNA 封装可抄） | —— 因此需要自建一层 native 桥 |

**结论**：官方唯一正确姿势是 **WGC**；没有"拿来即用、同时满足四项"的现成 Java 库。
OBS 是遇到疑难时的最佳参考源码。对 layered 等特殊窗口，OBS/各方同样受限（见 2.5），
业内通用兜底正是"抓整块显示器再按窗口矩形裁剪"——与 2.5 的降级设计一致。

### 2.2 两条可实现路线（决策记录）

- **路线 A（选定）：C++/WinRT 写单文件采集器 exe → Java 进程调用**
  微软官方支持路径，SDK 头在编译期校验接口签名；静态链接（`/MT`）成**单文件 exe，分发后与编译环境再无关系**。
  Java 侧仅 `ProcessBuilder` 传窗口句柄 + 读回 BMP。现有 PrintWindow/Robot 逻辑全部移除，不做兜底。
- **路线 B（否决）：纯 JNA 手写 WGC**
  免编译工具链，但需手写约 600~1000 行 COM vtable 且无现成社区绑定可抄，vtable 错一列即内存错乱，风险与维护成本远高于 A。

### 2.3 落地架构

```
Java (MatchClassifyAct)
 ├─ WindowFinder.findTarget()          JNA 枚举可见顶层窗口：标题命中关键字者，取"面积最大且优先非分层(WS_EX_LAYERED)"
 ├─ ScreenCaptureService.captureWindow()
 │    ├─ 把采集器从 classpath /native/win-x64/WindowsCapture.exe 解压到 %TEMP%\mca-windows-capture\
 │    └─ ProcessBuilder 调用:
 │         WindowsCapture.exe --hwnd <句柄> --out <临时.bmp> --timeout-ms <cfg>
 ├─ ImageIO 读回 BufferedImage → savePng() → capture/IMG_yyyyMMdd_HHmmss.png（原始截图；标注后连同数据移入 classify/）
 ├─ WindowCaptureTask                  @Scheduled(fixedDelay=interval-ms)：每帧（截图+尺寸校验+去重比对）处理完再等 interval-ms 取下一帧（处理耗时顺延、绝不叠帧；带 paused 开关，默认关闭，需页面手动「开启截图」）
 ├─ WindowResizer                      截图尺寸 ≠ capture.resize-width×resize-height 时，SetWindowPos 把窗口外框强制缩放到截图恰好达标（不达标不保存，由任务循环自动一直调窗重试）
 ├─ CaptureControlController           /api/capture/status|pause|resume：右上角「开启/暂停截图」（默认关闭）
 ├─ ui/BrowserLauncher                 启动就绪后自动以 Edge/Chrome 应用窗口打开控制台网页（ui.auto-open=false 可关）
 ├─ act/（执行模式，见 2.7）ExecutionService 执行循环：周期「截图（与标注模式同一套调窗对齐）→ FrameClassifier 像素比对识别 → 发布 Snapshot」
 │    ├─ ExecuteController              /api/execute/status|start|stop|refresh|latest|frame|act
 │    ├─ FrameClassifier                当前画面与 classify/ 已标注样本逐张比对 → 最近似分类与差异度（≤ execute.match-threshold-percent 才判已识别）
 │    └─ WindowClicker                  把「动作坐标（图片像素=窗口坐标）」发成鼠标左键点击：post=向窗口 PostMessage / screen=SetCursorPos+mouse_event
 └─ 控制台（web，人工打标 → 喂给 Classify/Act 的样本数据，见 2.6；执行模式页见 2.7）
      ├─ AnnotateController            /api/annotate/*：列表 / 图片 / 标注读写
      ├─ AnnotatePageController        / 与 /annotate → static/annotate.html
      ├─ SystemController              POST /api/system/shutdown：退出程序（结束截图任务与服务）
      ├─ ThinkController + ThinkService 汇总分析（仅供人工目检展示，不参与标注）：同「分类标注（动作一致）」自动分析 → 产物 summary/<分类标注>/same|max|avg|maj8|avg8|maj32|avg32.png + info.json
      └─ static/annotate.html          标注单页：看图 / 填分类标注 / 定匹配动作(点坐标) / 汇总分析 → 中心表 data.json + 样本归属 json

Native (native/windowcap/windowcap.cpp → WindowsCapture.exe，C++/WinRT /MT 静态链接)
 └─ WGC：按窗口创建 GraphicsCaptureItem → 等第一帧 → CopySubresourceRegion
      → Map 读像素 → 写 24-bit BMP
      ├─ 普通窗口：CreateForWindow(hwnd)（抓窗口自身合成内容，无视遮挡/前台/缩放）
      └─ 分层窗口(WS_EX_LAYERED)：自动降级 CreateForMonitor + 窗口矩形裁剪（见 2.5）
```

### 2.4 命令行参数与退出码（WindowsCapture.exe）

```
WindowsCapture.exe --hwnd <十进制句柄> --out <输出.bmp> [--timeout-ms <毫秒>]
WindowsCapture.exe --title <完整标题> --out <输出.bmp> [--timeout-ms <毫秒>]
WindowsCapture.exe --search <标题子串> --out <输出.bmp> [--timeout-ms <毫秒>]     // 取第一个匹配
WindowsCapture.exe --pid <进程ID> --out <输出.bmp> [--timeout-ms <毫秒>]           // 进程主窗口
WindowsCapture.exe --process <进程文件名> --out <输出.bmp> [--timeout-ms <毫秒>]   // 如 MuMuNxDevice.exe
```

退出码为**分段编号：万位=错误大类，低位=具体位置**，每类留足扩展空间（完整码表以
`native/windowcap/windowcap.cpp` 文件头为准）：

| 退出码 | 含义 | Java 侧日志 |
|-------|------|------------|
| 0 | OK，BMP 已写出（stdout：`OK WxH`） | debug |
| 10000–10007 | **参数问题**（命令行写错），每类一码：未知参数 10000、缺 `--out` 10001、无定位方式 10002、多种定位互斥 10003、参数缺值 10004、`--hwnd` 非法 10005、`--pid` 非法 10006、`--timeout-ms` 非法 10007 | warn 原样输出 |
| 20000 | **找不到窗口** / 无匹配可见窗口 / `--hwnd` 句柄无效 | warn（`logHelperFailure` 分支） |
| 20001 | **窗口最小化** / 无可捕获内容 | debug 跳过 |
| 20002–20003 | 窗口问题：分层窗完全在屏幕外 20002、创建捕获项失败（受保护/不可捕获）20003 | warn 原样输出 |
| 20004 | **抓帧超时**（窗口未产出画面；Java 看门狗强制结束也统一此码） | warn |
| 20005 | 窗口问题：帧内容尺寸为空 | warn 原样输出 |
| 30000–30010 | **程序内部错误与未预期异常**，每个失败点一码：D3D 设备 30000 / 包装 30001 / 取回 30002、显示器信息 30003、DXGI 接口 30004、D3D 纹理 30005、staging 30006、Map 30007、写文件 30008、WinRT 异常 30009、未知 30010（HRESULT 见 stderr） | warn 原样输出 |

输出 stdout/stderr 均为 UTF-8（C++ 侧 `_setmode(_fileno(...), _O_U8TEXT)`），
Java 侧统一 `redirectErrorStream(true)` + UTF-8 解码。

### 2.5 分层窗口（LAYERED）硬限制：优先选对窗口，降级仅兜底

**关键经验**：像 MuMu 这类模拟器会开出多个 "MuMu" 窗口——有的只是**分层悬浮框架**
（`WS_EX_LAYERED`），有的才是**真正承载目标程序画面的渲染窗口**（非分层，如 MuMu 的 `MuMuNxDevice`）。
实测发现承载画面的那个窗口可被 `CreateForWindow` **直接按窗口抓取**（画面完整、不靠前台/遮挡），
而分层框架窗回退"显示器裁剪"得到的是黑屏。因此**最优先的不是兜底，而是选对窗口**：
Java 侧 `WindowFinder` 优先选择非 LAYERED 且面积最大的候选，仅当所有命中窗口都是分层窗口时才交给兜底逻辑。

**实验结论**：WGC 的 `CreateForWindow` 对 `WS_EX_LAYERED` 分层窗口稳定返回
`0x80070057 (E_INVALIDARG)`——这是 WGC 合成 API 的硬限制（非虚拟桌面问题，已实测排除：
`IsWindowOnCurrentVirtualDesktop=True` 仍失败；去掉 LAYERED 样式后仍失败，对照普通窗口全部成功）。

**降级设计**：采集器检测到 `WS_EX_LAYERED` 时自动改为
`IGraphicsCaptureItemInterop::CreateForMonitor(mon)` 抓整块显示器合成帧，
再按 `GetWindowRect ∩ MonitorRect` 用 `D3D11_BOX` 裁剪输出。

**降级路径的边界（重要）**：
- 抓的是"该矩形当前在屏幕上合成出来的画面" → 若窗口被其它窗口**部分遮挡**，遮挡内容会被截入；
  若窗口**部分移出屏幕**，移出部分被裁掉。这是显示器级抓取的固有语义。
- 仍要求窗口在当前桌面会话可见（不可见/最小化时 Monitor 捕获项无窗口内容可裁，同源限制）。
- 屏幕录屏/远程会话等场景下 DWM 合成内容可能不可用。

### 2.6 控制台：给截图打标（生产喂给 Classify/Act 的样本数据）

截图只是原料（画面来自你通过标题关键字指定的**任意图形程序窗口**），分类标注（分类名）与匹配动作需要人来确认。MCA 内置一个**零外部依赖**的网页控制台：

- **入口**：`mvn package` 起服后**自动打开**控制台网页（优先以 Edge/Chrome **应用窗口**方式，退出程序时页面可脚本自关；找不到浏览器时回退系统默认浏览器）。如需禁止自动打开：`java -jar … --ui.auto-open=false`，再手动访问 `http://localhost:8080/annotate`（根路径 `/` 亦跳入）。
- **使用**：左上角「工作模式」下拉在 **标注模式 / 执行模式** 间切换（标注模式为默认；执行模式=实时识别 + 一键执行，见 §2.7）。标注模式顶栏导航分四段 —— **全部 / 未标注 / 已标注 / 汇总分析**（前三种看普通截图列表）→ 中央看图 → 右侧编辑：
  - **分类标注**（界面文本，JSON 字段 `state`）：当前画面的分类名（图形程序的画面状态，如 登录页 / 主界面 / 弹窗 / 无响应…）；**所有已使用过的分类标注**以可点击 chip 展示在输入框上方（带边框高亮，右侧数字 = 使用它的已标记图数量），点一下即填入；正在使用中的会绿色高亮。分类标注将作为汇总分析产物目录名（`summary/<分类标注>/`），故**不可包含 `\ / : * ? " < > |` 等无法作为文件名的符号、也不能以 `.` 结尾**（输入时非法符号自动剔除，保存前再次校验）；chip 内右侧 ✎ 可把该分类标注<b>整体重命名</b>——中心表 `data.json` 的 key 与使用该分类的全部样本 json `state` 批量改新名，并清理旧 `summary/<旧分类标注>/` 产物目录（图片样本不动，下次汇总分析按需重算）；
  - **匹配动作**（JSON 字段 `action`）：无动作 / 鼠标点击 —— 选"鼠标点击"后直接在图片上点一下即记录坐标；**一个分类标注只能对应一种匹配动作与一组坐标**（不同动作/坐标应视为不同分类）：该分类的「动作 + 坐标」是一份<b>分类级统一定义</b>，只存一份在 `classify/data.json`（样本 json 只记归属、不再逐张复制坐标）——分类<b>首次</b>打标时把所选动作与点击坐标固定为该分类定义；之后给同分类加样本，填上分类直接保存即可，坐标自动沿用统一定义（不必逐张再点）；在<b>已标注该分类的图</b>上改动作或重新点坐标再保存 = **重定义该分类**（全组同步，保存前会确认）。动作选择下方有实时提示（该分类统一定义的动作/坐标、已用张数），与统一定义不一致会提示并阻止保存；
  - 按钮：**保存并下一张 / 使用上次的标记 / 清除标记 / 重新加载**；保存时把分类定义写入中心表 `classify/data.json`、图片同名 json 只写归属 `{"state":"…"}`，并把 PNG 移入 `classify/`，随后<b>随机跳到一张尚未标注</b>的截图（更快覆盖不同画面，全部标完会提示）；
  - **自动带入上次标记**：保存成功后该标记自动作为"上次标记"，切到未标注图时会自动带入编辑区，逐张确认后连续保存即可；
  - 快捷键 `↑`/`↓` 切换图片、`Enter` 保存并下一张；列表每 10 秒自动静默刷新（有新增/变化才重绘），编辑中或页面切后台不发请求。
  - **布局**：右侧标注编辑区在窗口内**纵向居中**，标注控件集中在视野中线附近，方便连续点击；顶栏中部为四段导航（全部 / 未标注 / 已标注 / 汇总分析），**右上角保留「开启截图 / 暂停截图」与「退出程序」两个按钮**；
  - **开启 / 暂停截图（默认不开启）**：截图任务启动后默认关闭、不自动保存任何图；点击「开启截图」（`POST /api/capture/resume`）才按间隔开始后台截图，按钮随之变绿并显示「暂停截图」；再点一次（`POST /api/capture/pause`）即暂停（不再查找窗口、不保存新截图，控制台其余功能不受影响）。页面打开时会先 `GET /api/capture/status` 同步真实状态；
  - **截图结果右下角即时提示**：开启截图后每完成一轮都会即时提示——保存成功显示「已保存截图 `IMG_xx.png`」；画面与去重基准中的参考图（capture/ 原始截图 + classify/ 已标注样本的全部 PNG）差异小于 `capture.diff-threshold-percent`（默认 5%）被判定重复丢弃时显示「当前画面与截图「参考图名」差异小于 5%，本次不保存」。截图节拍 = **每帧处理完再等 `capture.interval-ms`（默认 1 秒）取下一帧**，画面静止判重丢弃时同样约每秒一拍。提示为**单条替换式**（新结果直接替换旧条、约 2 秒后自动淡出，不叠加刷屏）：后端每轮把结果写入 `/api/app/meta` 的 `shotNotice`（at/kind/name/pct），前端每 2 秒轮询取走按时间戳去重——节拍可快于轮询，轮询间隙内连续产生的多条中间结果只展示最新一条（属单条提示的预期行为）；
  - **启动历史重复清理（每次启动后台执行一次）**：按 `capture.diff-threshold-percent`（默认 5%）把 capture/（未标注原始截图）与 classify/（已标注样本）里<b>全部</b>已有截图做一遍重复清理——保留优先级为「已标注样本 &gt; 未标注截图」，同目录内保留较早一张；同尺寸且与已保留图平均像素差异 &lt; 阈值的判为重复并删除（删除 classify/ 下的重复样本时其同名 `.json` 一并移除，中心表 `data.json` 的分类定义不受影响，汇总分析产物会因样本数变化被自动标记待重算）。这样早期未开去重 / 旧版只对比少数参考图时期堆积的历史重复，重启一次即清掉，不用手工逐个翻。任务在独立后台线程执行、不阻塞启动，且先于首轮截图去重判定完成（避免与文件删除并发）；参考图缩略缓存也随本次任务一次性重建，后续逐帧去重直接命中。画面持续变化产生的新重复仍靠运行期去重挡（挡不掉的见 §五 待办）。
  - **服务端版本自检（后端更新后前端自动刷新）**：前端每 5 秒轮询一次 `GET /api/app/meta`，与页面打开时记录的 `codeTs` 基线比对——该值是「代码构建时间」（`java -jar` 取 jar 文件 mtime，开发目录直接跑取 `static/annotate.html` mtime）。后端重新打包并重启后 `codeTs` 变化，前端自动刷新加载新版页面；若此刻正在编辑未保存，会先提示并挂起刷新，等保存/清除/重新加载成功后再执行，不丢标注。页面从后台切回时也会立刻复核一次。
  - **启动自动开网页 · 退出自动关页**：程序启动就绪后自动用 Edge/Chrome **应用窗口**打开控制台（找不到则回退系统默认浏览器；`--ui.auto-open=false` 可关），截图默认不开启（见上）。点击「退出程序」→ `POST /api/system/shutdown` 结束整个服务进程（若恰有抓帧中的 `WindowsCapture.exe`，关闭钩子会先强制结束它），页面随后自动 `window.close()` **自己关掉**（应用窗口下可被脚本关闭；普通标签页受浏览器策略限制时，页面会显示提示与手动关闭按钮兜底）。若程序被外部结束（崩溃/杀进程/关控制台），页面每 5 秒的心跳探测连续失败后也会触发同样的自动关页。
  - **汇总分析（自动分组 · 仅供展示，不参与标注）**：顶栏第四段进入**分类标注工作台**——系统按「分类标注相同（匹配动作一致）」把已保存标注截图自动分组，统一一个队列展示。进入本段时，所有「样本 ≥2 且尚未生成对照图」的分组会被**自动异步分析**：逐像素比较后生成**七张对照图** —— `交集图`（每个像素取**覆盖大于 90%** 的值：覆盖率 = 该颜色出现的样本占比，>90% 才保留为不透明，其余透明）、`多数图`（每个像素取**覆盖率最多**的颜色）、`均值图`（每个像素取全部样本的 **RGB 均值**）、`1/8 多数图`（所有样本同一 8×8 块内全部像素合并，取出现次数最多的颜色）、`1/32 多数图`（同上按 32×32 块）、`1/8 均值图`（所有样本同一 8×8 块内全部像素合并，取全部像素的 RGB 均值）、`1/32 均值图`（同上按 32×32 块），主区同时显示覆盖率；结果<b>仅供人工目检参考、不参与标注决策</b>，不会写入任何图片标注或结论（画面标签一律以控制台的人工标注为准）。产物按分类标注落盘为 `summary/<分类标注>/same.png|max.png|avg.png|maj8.png|avg8.png|maj32.png|avg32.png`（分析信息：样本数 / 覆盖率 / 公共点击坐标 / 更新时间等写同目录 `info.json`），可随时整目录删除、下次分析按需重算。<b>单击任意一张图放大为原尺寸大图，再单击图片即还原</b>（按 Esc 也可关闭）。右栏顶部有**状态横幅**（样本不足 / 待分析 / 样本有变 / 对照图已生成·覆盖率），下方为【分类标注】【匹配动作】【原始截图张数】【分辨率】四项信息；主区在样本不足或待分析时也会显示对应占位提示而非空白。样本 <2 张的分组不分析并明确提示。列表（标题「分类标注列表（按匹配度）」）排序规则：**已分析分组按交集图像素覆盖率从低到高排列**——覆盖率越高说明该组截图彼此差异越小（多为同一画面反复截取、采样不足，参考价值反而低），放在前面的是截图差异更大、采样更充分的组合，便于优先目检 / 补采；同覆盖率按「分类标注 → 动作」文字升序；其余分组（未分析 / 样本有变待重算）随其后，其中样本 ≥2 的在前、**只有 1 张样本的分组恒排最后**。
- **坐标语义**：截图是窗口物理像素内容 → **图片像素 = 窗口相对坐标（Left, Top）**，后续 Act 阶段可直接据此执行鼠标动作。
- **数据布局**：图片与数据按“处理阶段”分目录（均相对程序运行目录，见 §四 目录结构）：
  - `capture/`：后台采集的**原始截图**（未标注，扁平存放，文件名 `IMG_yyyyMMdd_HHmmss.png`）；
  - `classify/`：**保存标注的瞬间** PNG 移入；图旁同名 json（`IMG_x.png → IMG_x.json`）**只记该图归属的分类**（见下），供后续 Classify/Act 匹配画面取用；
  - `classify/data.json`：**分类定义表（中心表）** —— 每个分类的动作与坐标只在此保存一份，是「分类 → 动作/坐标」的单一事实来源，后续 Act 阶段直接读它执行（读样本时由“样本 json 的 state + 表定义”合成完整标注，接口字段不变）：
    ```json
    { "schema": 1,
      "states": { "登录页": { "action": "click", "left": 640, "top": 360 } } }
    ```
  - `summary/<分类标注>/`：汇总分析产物（七张对照图 + `info.json`，样本取自 `classify/` 同标注组，可随时重算）。

  两张标注文件示例（动作坐标**只**在 `data.json`，样本 json 不重复）：

  ```json
  // classify/data.json   （states 中每个分类一条）
  { "登录页": { "action": "click", "left": 640, "top": 360 } }
  // classify/IMG_xxx.json（每张样本只写归属）
  { "state": "登录页" }
  ```

  `action`：`none`（无动作，仅分类标注）/ `click`（携带 `left/top`）。（JSON 字段沿用 `state/action`，界面统一称「分类标注 / 匹配动作」）。**历史数据自动迁移**：老版本“每张 json 自带 action/left/top”的全量写法，在服务首次访问时自动归纳——每个分类按众数生成一条统一定义写入 `data.json`，旧样本 json 就地瘦身为仅 `{state}`（读取兼容旧文件，迁移失败不丢数据，仅日志告警）。
- **API**：`GET /api/annotate/images`（列表含标注摘要，由“样本 state + 中心表定义”合成，同分类动作坐标一致）、`GET /api/annotate/image/{name}`（PNG）、
  `GET /api/annotate/defs`（**分类定义表**：全部已定义分类 `{state,action,left,top}`，含暂无样本图的定义；前端填分类时直接带出统一定义）、
  `GET / PUT / DELETE /api/annotate/mark/{name}`（标注 CRUD。PUT 语义：分类<b>首次</b>定义需提供动作与 click 坐标（写入 `data.json`）；该分类<b>已定义</b>则只登记样本归属、动作坐标一律以统一定义为准；在<b>已标注该分类的图</b>上改动作/坐标视为重定义该分类（全组同步））、
  `POST /api/annotate/rename`（分类标注整体改名 `{from,to}`：中心表 key 与使用该分类的全部样本 json `state` 一并更新，并清掉旧 `summary/<旧分类标注>/` 产物目录；目标名已被其它分类定义/图片使用或含非法文件名符号则 400）、
  `GET /api/app/meta`（版本探针：返回 `codeTs`=代码构建时间、`startedAt`=本次启动时间，前端据此检测服务端更新并自动刷新；另含 `shotNotice`=最近一次截图结果 `{at,kind,name,pct?}`（kind=`saved` 保存成功 / `dup` 差异过小丢弃，name 为对应截图文件名，pct 为丢弃阈值）与 `savedSeq`=累计保存张数等，供前端即时提示与列表刷新）、
  `GET /api/capture/status` / `POST /api/capture/pause` / `POST /api/capture/resume`（右上角「开启/暂停截图」：截图默认关闭，手动开启/暂停后台周期截图，不影响控制台）、
  `POST /api/system/shutdown`（控制台「退出程序」按钮调用：先响应页面，再结束截图任务并关闭整个服务）、
  `GET /api/annotate/think/groups`（分组清单：state+action、样本数、可分析性、是否已分析（七张产物齐全）、覆盖率、产物目录名 `dir`）、
  `POST /api/annotate/think/analyze`（异步分析所有「未分析或样本有变且 ≥2 张」的分组 → 重算七张对照图到 `summary/<dir>/` 并刷新 `info.json`）、
  `GET /api/annotate/think/task/{id}`（轮询分析任务进度）、
  `GET /api/annotate/think/img/{kind}?dir={dirB64}`（`kind = same | max | avg | m8 | a8 | m32 | a32`，分别对应交集图 / 多数图 / 均值图 / 1-8 多数图 / 1-8 均值图 / 1-32 多数图 / 1-32 均值图；`dirB64` = 分类标注产物目录名的「UTF-8 → Base64」，纯 ASCII 传输、不受容器字符集影响）、
  `POST /api/annotate/delete`（`{name: 截图文件名}`：把该 PNG 连同同名标注 `.json` 一起移入<b>系统回收站</b>（PowerShell `SendToRecycleBin` 软删除、可还原，控制台不再显示）；只允许删除本程序输出的 `img_*.png`——该类文件只经「`.tmp` 写入完成 → 原子改名」产生，出现即完整，`.tmp` 半成品不会出现在列表中，不存在则 404）。

### 2.7 执行模式：实时画面识别 + 一键执行（Act 阶段已接入）

执行模式把工程最终链路接起来：**后台截图 → 实时识别画面分类 → 人工确认后向目标窗口发送鼠标点击**。
它复用标注阶段同一套「找窗口 → WGC 截图 → resize 把窗口对齐到样本尺寸」机制，但**截图只用于识别与展示、不写盘**。
识别方式：把当前画面与 `classify/` 里每一张已标注样本做逐像素比较（横纵每隔 4 像素抽 1 点，约 1/16 画面，样本带 mtime 缓存），
按「分类标注」聚合、每个分类只留差异最小的一张样本，**差异最小者 = 最近似分类**；仅当最小差异
≤ `execute.match-threshold-percent`（默认 25%）才判定为「已识别」，否则界面展示最近似分类作参考、**不执行动作**（防误点）。

- **入口与切换**：控制台左上角「工作模式」下拉在「标注模式 ⇄ 执行模式」间切换（默认标注模式）。执行模式下仅显示执行界面
  （标注筛选/列表/汇总分析等隐藏，右上角「完全退出」保留）；有未保存标注时需先「保存并下一张」或「清除标记」才能切走。
- **画面区（左，实时最新帧）**：显示最近一次识别对应的**整幅窗口截图**（`GET /api/execute/frame`）。截图 = 窗口内容物理像素，
  与标注样本逐像素对齐（截出来不是 `capture.resize-width×height` 会自动用 `SetWindowPos` 调窗后重截验证，最多 3 次）。
  **命中「鼠标点击」动作时，识别出的点击点直接叠加标在图上**——绿色圆点 + 十字参考线，左下角角标显示
  「分类已识别 · 点击 (x,y)」；单击图片可放大查看大图。画面会随执行循环周期刷新（页面 1.5 秒轮询取快照，快照变化才重取帧）。
- **识别信息（右栏）**：识别时间（快照时刻）、截图/识别耗时、**分类标注**（已识别绿显；未识别红显「未识别 · 最近似「xx」」）、
  **匹配动作**（鼠标点击 / 无动作）、点击坐标、差异度（与阈值对照是否达标）、样本规模（同尺寸 / 全部张数）、目标窗口标题；
  下方另列**候选分类**（差异度由小到大，带命中样本文件名），便于人工核对识别可信度。
- **执行循环（默认关闭）**：「▶ 开始执行循环」后按 `execute.interval-ms`（默认 2 秒）周期「截图 → 识别 → 刷新画面与结果」；
  停止循环时也可用「立即识别一次」手动触发单轮。循环状态与截图任务相互独立，可同时开启（各自调度、都朝同一目标尺寸收敛）。
- **触发执行（按钮）**：识别出「鼠标点击」动作（已识别 + 坐标完整）后「触发执行」按钮才可用。点击后后端
  **先按最新画面复核识别一次**（避免界面停留期间画面已变化），确认仍命中且坐标完整后向目标窗口发送一次鼠标左键点击：
  默认 `post` —— 直接向目标窗口 `PostMessage WM_LBUTTONDOWN / WM_LBUTTONUP`（坐标 = 窗口客户区相对坐标 = 图片像素，
  不要求窗口在前台、不抢占用户鼠标）；`execute.click-mode=screen` 时改为 `SetForegroundWindow + SetCursorPos + mouse_event`
  模拟真实输入（要求窗口可见、不被遮挡）。执行结果（成功/失败原因、动作坐标、换算后的屏幕坐标）即时提示在界面。
- **API**：`GET /api/execute/status`（运行开关与参数 intervalMs / thresholdPercent / clickMode）、
  `POST /api/execute/start` / `stop`（开/关执行循环）、`POST /api/execute/refresh`（立即截图并识别一轮）、
  `GET /api/execute/latest`（最近一次识别快照 JSON：`at` 识别时间、窗口信息、`recognized`、`state` 分类标注、`action` 动作、
  `left/top` 点击坐标、`bestDiffPercent` 差异度、样本规模、候选分类、截图/识别耗时；尚未识别时为占位快照）、
  `GET /api/execute/frame?at=…`（快照对应 PNG，`at` 仅作浏览器缓存去抖，未命中时返回最新画面；无画面 404）、
  `POST /api/execute/act`（**触发执行**：复核最新画面后向目标窗口发送鼠标点击；业务未就绪也返回 200 + `ok:false` + 原因，
  便于前端直接提示）。
- **依赖前提**：识别要求 `classify/` 里有「与目标窗口同尺寸 + 已标注分类（动作=鼠标点击带坐标）」的样本——
  请先在标注模式把目标程序各画面状态标注好（resize 已让窗口与样本尺寸逐像素对齐）。样本不足/尺寸不符时界面会给出明确提示。

---

## 三、构建 / 运行 / 配置

### 3.1 native 采集器（仅改 C++ 时需要）

前置：VS BuildTools 的 C++ 工具集 + Windows 11 SDK（`C:\Program Files (x86)\...` 默认路径，脚本已写死）。

```powershell
cd MatchClassifyAct\native\windowcap
.\build.bat                     # 产出 WindowsCapture.exe（/MT 静态单文件）
Copy-Item WindowsCapture.exe ..\..\src\main\resources\native\win-x64\   # 同步进 jar 资源
```

### 3.2 Java 构建 / 运行

本机 Maven 默认绑定 JDK 8，需临时指定 JDK 17：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/MatchClassifyAct-0.0.1-SNAPSHOT.jar
```

运行需**真实桌面会话**（入口已强制 `java.awt.headless=false`），不要用无桌面的服务/SSH 跑。
启动后自动弹出控制台网页（Edge/Chrome 应用窗口，见 2.6；`--ui.auto-open=false` 可关），但**截图默认不开启**——需在页面点「开启截图」手动开始后台截图。

### 3.3 配置项（业务可调项默认值固化在 Java，可外部覆盖）

`application.properties` 只保留应用名与日志三项基础配置；**业务相关可调项不再写入该文件**，
默认值与说明统一固化在代码里：

- `capture.*` → `config/CaptureProperties`（`@ConfigurationProperties` 字段默认值）；
- `ui.*` → `ui/BrowserLauncher`（`@Value` 注解里的默认值）；
- 内嵌 Tomcat 的 URI / 查询串解码字符集（固定 UTF-8）→ `config/WebServerConfig`。

需要覆盖时用 Spring 标准外部化配置即可（优先级高于代码默认值，**无需改代码**）：
在 `application.properties` 追加同名键、设置环境变量（如 `CAPTURE_INTERVAL_MS`），
或启动时加命令行参数（如 `java -jar … --capture.interval-ms=1000`，命令行优先级最高）。

| 键 | 默认值 | 说明 |
|------|--------|------|
| `capture.window-keywords` | `MuMu模拟器,MuMu安卓设备,MuMu` | 目标图形程序窗口标题关键字（默认即演示环境 MuMu，改成想自动化的任意 GUI 程序标题即可），命中任一即候选，多窗口取面积最大 |
| `capture.interval-ms` | `1000` | 相邻两帧截图的最小等待（毫秒），**fixedDelay 语义**：每处理完一帧（截图 → 尺寸校验 → 与去重基准匹配比对 → 保存/丢弃）后再等这么久取下一帧——单帧处理耗时多长就顺延多长，匹配比对没完成不会开始下一帧、绝不叠帧；截图默认不开启，手动「开启截图」后运行 |
| `capture.capture-dir` | `capture` | 原始截图（未标注）保存目录（相对运行目录，自动创建，已被 .gitignore 忽略） |
| `capture.classify-dir` | `classify` | 已标注样本（PNG + 归属 json `{state}`）目录；同目录 `data.json` 为分类定义表（每分类动作/坐标一份） |
| `capture.summary-dir` | `summary` | 汇总分析产物保存目录（内部按「分类标注」分目录，样本取 classify/，可随时重算） |
| `capture.output-dir` | `captures` | **旧版**单目录（截图/标注/汇总同根）：仅启动时自动迁移用，之后不再读写 |
| `capture.capture-timeout-ms` | `5000` | 传给采集器的内部抓帧超时（毫秒） |
| `capture.resize-width` | `1280` | 截图目标宽（物理像素）：开启截图后，凡是截出来不是「宽×高」的帧一律不保存，并自动用 `SetWindowPos` 把窗口**整体外框**缩放（最大化先还原）后重截验证，直到截出的 PNG 恰好等于目标宽×高才保存。与 `resize-height` 同时 `>0` 才启用（默认 1280×720）；任一设 `0` 则关闭尺寸校验、按旧行为原样保存。注意：需把目标程序内部分辨率/方向配置成同尺寸（如 MuMu 设 1280x720、16:9），否则画面可能变形/带黑边 |
| `capture.resize-height` | `720` | 截图目标高（物理像素），与 `capture.resize-width` 配套 |
| `capture.diff-threshold-percent` | `5` | 像素差异去重阈值（%，0~100）：开启后，当前画面保存前须与**去重基准**——capture/（原始截图）与 classify/（已标注样本）两目录下的**全部** PNG（内部以缩略图快速比对，判定口径同整图平均像素差异）——中<b>每一张</b>的平均像素差异都 ≥ 该值才保存，只要与任意一张过像（画面静止、回到过以前的状态、或与某张已标注样本几乎相同）即视为重复帧丢弃，避免一边标注入库存样本、一边 capture/ 又落盘几乎一模一样的截图；截图被标注移入 classify/ 或程序重启后仍能对上历史任意一张。`0` 或负数 = 关闭去重。截图节拍 = 每帧处理完再等 `capture.interval-ms`（默认 1 秒，fixedDelay 顺延不叠帧），每轮结果（保存成功 / 判定重复丢弃）都会即时经 `/api/app/meta` 的 `shotNotice` 推给控制台，右下角以单条替换式轻提示展示约 2 秒：「已保存截图 xx.png」或「当前画面与截图「xx.png」差异小于 5%，本次不保存」——画面静止判重丢弃时同样约每秒一拍，单条替换、不叠加刷屏。程序<b>每次启动</b>还会在后台按同阈值把 capture/ + classify/ 已有全部截图做一遍历史重复清理（删除与保留图差异低于阈值的重复，见 §2.6） |
| `ui.auto-open` | `true` | 启动就绪后自动以 Edge/Chrome 应用窗口打开控制台网页（找不到则回退系统默认浏览器） |
| `ui.path` | `/annotate` | 自动打开的网页路径（根路径 `/` 亦跳入） |
| `ui.window-size` | `1760x990` | 控制台应用窗口尺寸（宽x高）；`0x0` = 不指定、交给系统 |
| `ui.center` | `true` | 控制台应用窗口是否在屏幕可用区域内居中展示 |
| `execute.interval-ms` | `2000` | 执行模式「截图 → 识别」循环间隔（毫秒）；执行循环默认不开启，页面「开始执行循环」后按此周期运行 |
| `execute.match-threshold-percent` | `25` | 画面识别阈值（平均像素差异百分比，0~100）：与 classify/ 样本逐张比对后取差异最小的分类为最近似；≤ 阈值才判定「已识别」，否则视为未识别（界面展示最近似参考、不执行动作） |
| `execute.click-mode` | `post` | 鼠标点击执行方式：`post`=向目标窗口 PostMessage WM_LBUTTONDOWN/UP（后台，不需前台）；`screen`=SetForegroundWindow + SetCursorPos + mouse_event 真实输入（要求窗口可见、不被遮挡） |

---

## 四、代码 / 目录结构

```
MatchClassifyAct/
├─ pom.xml                                   Spring Boot 3.2.5 + JNA 5.14.0(jna+jna-platform) + Lombok + starter-web
├─ native/windowcap/
│  ├─ windowcap.cpp                          WGC 采集器源码（C++/WinRT，含分层降级逻辑）
│  └─ build.bat                              一键编译脚本 → WindowsCapture.exe
└─ src/main/
   ├─ java/cn/moonlord/mca/
   │  ├─ MatchClassifyActApplication.java    入口：@EnableScheduling，强制非 headless + Per-Monitor DPI
   │  ├─ config/CaptureProperties.java       capture.* 业务可调项默认值（Java 固化，可外部覆盖）
   │  ├─ config/ExecuteProperties.java      execute.* 执行模式可调项默认值（Java 固化，可外部覆盖）
   │  ├─ config/StoragePaths.java            capture.* 目录配置求值：capture/ classify/ summary/ 三分区
   │  ├─ config/WebServerConfig.java         内嵌 Tomcat 请求 URI/查询串统一按 UTF-8 解码（代码固化）
   │  ├─ config/LegacyStorageMigrator.java   ApplicationRunner：旧版 captures/ 单目录启动时自动迁移到三分区
   │  ├─ ui/BrowserLauncher.java             ApplicationReadyEvent：自动以 Edge/Chrome 应用窗口打开控制台网页（ui.* 默认值）
   │  ├─ capture/
   │  │  ├─ WindowInfo.java                  窗口模型：句柄 / 标题 / 屏幕矩形 / 是否最小化
   │  │  ├─ WindowFinder.java                findTarget() 取"面积最大且优先非分层(WS_EX_LAYERED)"的窗口
   │  │  ├─ ScreenCaptureService.java        captureWindow() 调 WindowsCapture.exe；savePng() 原子落盘
   │  │  │                                   （.tmp→改名）并缓存 latestImage/latestFile；去重基准缩略缓存、启动历史重复清理
   │  │  ├─ WindowResizer.java               截图尺寸 ≠ 目标宽×高时按尺寸差用 SetWindowPos 强制缩放窗口（最大化先还原、越界平移回屏幕内），由任务循环重截验证直至 PNG 恰好达标
   │  │  ├─ CaptureControlController.java    /api/capture/status|pause|resume：开启/暂停周期截图（默认关闭）
   │  │  ├─ WindowCaptureTask.java           ApplicationRunner + @Scheduled 周期截图（默认 paused=true 不截图，页面手动开启）
   │  │  └─ StartupDedupCleaner.java         ApplicationReadyEvent：后台执行启动历史重复清理（同 diff-threshold 阈值删历史重复）+ 去重基准缩略缓存预热
   │  ├─ act/                               执行模式（实时识别 + 一键执行，见 §2.7）
   │  │  ├─ ExecuteController.java          REST /api/execute/*：status|start|stop|refresh|latest|frame|act
   │  │  ├─ ExecutionService.java           执行循环主轴：找窗 → 截图（尺寸不符自动调窗重截）→ FrameClassifier 识别 → Snapshot（画面仅内存缓存、不写盘）；act() 复核最新画面后交 WindowClicker 发送鼠标点击
   │  │  ├─ FrameClassifier.java            画面识别：当前帧与 classify/ 样本（每 4 像素取 1 点、带缓存）逐张比对，最近似分类 ≤ execute.match-threshold-percent 判定已识别
   │  │  └─ WindowClicker.java              鼠标左键点击：post=向目标窗口 PostMessage WM_LBUTTONDOWN/UP；screen=SetForegroundWindow+SetCursorPos+mouse_event
   │  └─ mark/
   │     ├─ CaptureMark.java                 标注模型：state(分类标注) / action(匹配动作 none|click) / left / top（API 字段形状）
   │     ├─ ClassifyStore.java               分类定义中心表 classify/data.json：读样本=“归属 state + 表定义”合成；保存/改名；启动自动归纳旧数据迁移
   │     ├─ AnnotateController.java          REST /api/annotate/*：列图/PNG/标注 CRUD/defs/rename/delete（列图跨 capture/ + classify/ 双根）
   │     ├─ RecycleBin.java                  PowerShell SendToRecycleBin：把 PNG+同名标注移入系统回收站（可还原）
   │     ├─ AnnotatePageController.java      / 与 /annotate → 转发 static/annotate.html
   │     ├─ SystemController.java            POST /api/system/shutdown：退出程序（结束截图任务与服务）
   │     ├─ AppMetaController.java           GET /api/app/meta：版本探针（codeTs=代码构建时间，前端据此自动刷新）
   │     ├─ ThinkController.java             /api/annotate/think/*：groups / analyze / task / img
   │     └─ ThinkService.java                自动分组分析（产物落 summary/<分类标注>/：same|max|avg|maj8|avg8|maj32|avg32.png + info.json，单线程异步池，纯展示不参与标注）
   └─ resources/
      ├─ application.properties              仅应用名与日志格式/级别等基础配置（业务调参默认值见代码）
      ├─ static/annotate.html                控制台单页（标注 / 执行双模式，无外部 CDN，离线可用）
      └─ native/win-x64/WindowsCapture.exe   采集器产物（随 jar 打包，运行时解压到 %TEMP%）

capture/                                     原始截图目录（自动创建；未标注前只在这里，扁平存放）
   └─ IMG_yyyyMMdd_HHmmss.png                后台采集的原始画面（图形程序窗口内容）
classify/                                    已标注：图片 + 同名 .json（扁平存放，保存标注瞬间从 capture/ 移入）
   ├─ IMG_yyyyMMdd_HHmmss.png                已标注截图
   └─ IMG_yyyyMMdd_HHmmss.json               标注数据 { state, action, left?, top? }
summary/                                     汇总分析产物（按「分类标注」分目录，仅供展示、可随时整目录删除重算）
   └─ <分类标注>/
      ├─ same.png                            交集图（每个像素取覆盖 >90% 的主流颜色，不足则透明）
      ├─ max.png                             多数图（每个像素取覆盖率最多的颜色）
      ├─ avg.png                             均值图（每个像素取全部样本的 R/G/B 均值）
      ├─ maj8.png                            1/8 多数图（全部样本同一 8×8 块内所有像素合并，取出现次数最多的颜色）
      ├─ avg8.png                            1/8 均值图（全部样本同一 8×8 块内所有像素合并，取 RGB 均值）
      ├─ maj32.png                           1/32 多数图（同上，块为 32×32）
      ├─ avg32.png                           1/32 均值图（同上，块为 32×32）
      └─ info.json                           分析记录（state/action、样本数、覆盖率、公共点击坐标、更新时间等）
```

> 说明：旧版单目录 `captures/`（含 `sum/`、`think/` 等）已废弃；若旧目录仍存在，
> 程序启动时由 `LegacyStorageMigrator` 自动迁移到上面的 capture/ + classify/ + summary/ 三分区。

---

## 五、已知限制 & 待办

**已搞清的固有限制**（成因已确认，非 bug、无待办，详见 §2.5）：
- WGC 无法 `CreateForWindow` 直抓 LAYERED 分层窗口（返回 `E_INVALIDARG`）；仅当所有候选都是
  分层窗口时才走"显示器捕获 + 窗口矩形裁剪"兜底：被遮挡部分截到遮挡内容、移出屏幕部分被裁掉。
- 目标窗口在**其它 Windows 虚拟桌面**时 WGC 拿不到合成表面（普通窗口亦然，API 限制）。
- 控制台中文乱码是终端 GBK 显示问题（采集器与日志均 UTF-8），可先
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8` 再查看。

**真待办**：
1. 同时开多个相同目标程序（如多开几个 MuMu 模拟器）时按标题取"面积最大"，无法区分指定实例。
2. 持续落盘 PNG 占用磁盘（帧间最小间隔默认 1 秒，画面变化越快落盘越密）：`capture.diff-threshold-percent` 去重（基准 = capture/ + classify/ 全部 PNG）可挡掉“画面静止”与“回到已存画面/已标注样本”的重复帧，但画面持续变化时仍会不断积累；长跑注意清理 `capture/`（`classify/` 是要保留的样本；`summary/` 可随时重算后整目录删掉）。后续方向：磁盘配额/自动滚动清理、或对“新画面才保存”做更细的变化判断。
3. 执行模式的画面识别目前是「整帧像素抽样比对（约 1/16）」：同一画面状态需与样本像素高度一致，轻微光照 / 色偏 / 动态区域都会推高差异度判为未识别；带明显动画或环境变化的分类需更多样本覆盖，后续可升级局部 ROI / 特征匹配提高鲁棒性。
4. 「识别后执行」目前由人在执行页点「触发执行」确认（安全优先、防误点）；尚未做「识别即自动执行」的策略链与执行历史记录，可按需扩展。
