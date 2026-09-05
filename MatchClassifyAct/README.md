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

> 当前状态：已完成【后台画面采集（WGC）+ 控制台人工标注】阶段，Match / Classify / Act 待接入（见 §五 待办）。

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
 ├─ WindowCaptureTask                  @Scheduled 每 interval-ms 一轮（带 paused 开关；默认关闭，需页面手动「开启截图」）
 ├─ WindowResizer                      开启截图时把目标窗口客户区宽度一次缩放到 capture.resize-width（物理像素）
 ├─ CaptureControlController           /api/capture/status|pause|resume：右上角「开启/暂停截图」（默认关闭）
 ├─ ui/BrowserLauncher                 启动就绪后自动以 Edge/Chrome 应用窗口打开控制台网页（ui.auto-open=false 可关）
 └─ 控制台（web，人工打标 → 喂给后续 Classify/Act 的样本数据，见 2.6）
      ├─ AnnotateController            /api/annotate/*：列表 / 图片 / 标注读写
      ├─ AnnotatePageController        / 与 /annotate → static/annotate.html
      ├─ SystemController              POST /api/system/shutdown：退出程序（结束截图任务与服务）
      ├─ ThinkController + ThinkService 汇总分析（仅供人工目检展示，不参与标注）：同「分类标注（动作一致）」自动分析 → 产物 summary/<分类标注>/same|max|avg|maj16|avg16|maj64|avg64.png + info.json
      └─ static/annotate.html          标注单页：看图 / 填分类标注 / 定匹配动作(点坐标) / 汇总分析 → 同名 .json

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
- **使用**：顶栏导航分四段 —— **全部 / 未标注 / 已标注 / 汇总分析**（前三种看普通截图列表）→ 中央看图 → 右侧编辑：
  - **分类标注**（界面文本，JSON 字段 `state`）：当前画面的分类名（图形程序的画面状态，如 登录页 / 主界面 / 弹窗 / 无响应…）；**所有已使用过的分类标注**以可点击 chip 展示在输入框上方（带边框高亮，右侧数字 = 使用它的已标记图数量），点一下即填入；正在使用中的会绿色高亮。分类标注将作为汇总分析产物目录名（`summary/<分类标注>/`），故**不可包含 `\ / : * ? " < > |` 等无法作为文件名的符号、也不能以 `.` 结尾**（输入时非法符号自动剔除，保存前再次校验）；chip 内右侧 ✎ 可把该分类标注<b>整体重命名</b>——该分类下全部标注 json 的 `state` 批量改为新名，并清理旧 `summary/<旧分类标注>/` 产物目录（图片样本不动，下次汇总分析按需重算）；
  - **匹配动作**（JSON 字段 `action`）：无动作 / 鼠标点击 —— 选"鼠标点击"后直接在图片上点一下即记录坐标；**一个分类标注只能对应一种匹配动作**（不同动作应视为不同分类）：动作选择下方有实时提示（该分类标注已被多少张图使用、统一动作是什么），与既有动作不一致会提示并阻止保存；
  - 按钮：**保存并下一张 / 使用上次的标记 / 清除标记 / 重新加载**；保存时写入**图片同名 `.json`**，并把 PNG + json 一起移入 `classify/`，随后<b>随机跳到一张尚未标注</b>的截图（更快覆盖不同画面，全部标完会提示）；
  - **自动带入上次标记**：保存成功后该标记自动作为"上次标记"，切到未标注图时会自动带入编辑区，逐张确认后连续保存即可；
  - 快捷键 `↑`/`↓` 切换图片、`Enter` 保存并下一张；列表每 10 秒自动静默刷新（有新增/变化才重绘），编辑中或页面切后台不发请求。
  - **布局**：右侧标注编辑区在窗口内**纵向居中**，标注控件集中在视野中线附近，方便连续点击；顶栏中部为四段导航（全部 / 未标注 / 已标注 / 汇总分析），**右上角保留「开启截图 / 暂停截图」与「退出程序」两个按钮**；
  - **开启 / 暂停截图（默认不开启）**：截图任务启动后默认关闭、不自动保存任何图；点击「开启截图」（`POST /api/capture/resume`）才按间隔开始后台截图，按钮随之变绿并显示「暂停截图」；再点一次（`POST /api/capture/pause`）即暂停（不再查找窗口、不保存新截图，控制台其余功能不受影响）。页面打开时会先 `GET /api/capture/status` 同步真实状态；
  - **服务端版本自检（后端更新后前端自动刷新）**：前端每 5 秒轮询一次 `GET /api/app/meta`，与页面打开时记录的 `codeTs` 基线比对——该值是「代码构建时间」（`java -jar` 取 jar 文件 mtime，开发目录直接跑取 `static/annotate.html` mtime）。后端重新打包并重启后 `codeTs` 变化，前端自动刷新加载新版页面；若此刻正在编辑未保存，会先提示并挂起刷新，等保存/清除/重新加载成功后再执行，不丢标注。页面从后台切回时也会立刻复核一次。
  - **启动自动开网页 · 退出自动关页**：程序启动就绪后自动用 Edge/Chrome **应用窗口**打开控制台（找不到则回退系统默认浏览器；`--ui.auto-open=false` 可关），截图默认不开启（见上）。点击「退出程序」→ `POST /api/system/shutdown` 结束整个服务进程（若恰有抓帧中的 `WindowsCapture.exe`，关闭钩子会先强制结束它），页面随后自动 `window.close()` **自己关掉**（应用窗口下可被脚本关闭；普通标签页受浏览器策略限制时，页面会显示提示与手动关闭按钮兜底）。若程序被外部结束（崩溃/杀进程/关控制台），页面每 5 秒的心跳探测连续失败后也会触发同样的自动关页。
  - **汇总分析（自动分组 · 仅供展示，不参与标注）**：顶栏第四段进入**分类标注工作台**——系统按「分类标注相同（匹配动作一致）」把已保存标注截图自动分组，统一一个队列展示。进入本段时，所有「样本 ≥2 且尚未生成对照图」的分组会被**自动异步分析**：逐像素比较后生成**七张对照图** —— `交集图`（每个像素取**覆盖大于 90%** 的值：覆盖率 = 该颜色出现的样本占比，>90% 才保留为不透明，其余透明）、`多数图`（每个像素取**覆盖率最多**的颜色）、`均值图`（每个像素取全部样本的 **RGB 均值**）、`1/16 多数图`（每张样本先按 16×16 块内取覆盖率最多颜色压缩到约 1/16 尺寸，再跨样本取覆盖率最多）、`1/64 多数图`（同上按 64×64 块）、`1/16 均值图`（每张样本先按 16×16 块内取 RGB 均值压缩，再跨样本取均值）、`1/64 均值图`（同上按 64×64 块），主区同时显示覆盖率；结果<b>仅供人工目检参考、不参与标注决策</b>，不会写入任何图片标注或结论（画面标签一律以控制台的人工标注为准）。产物按分类标注落盘为 `summary/<分类标注>/same.png|max.png|avg.png|maj16.png|avg16.png|maj64.png|avg64.png`（分析信息：样本数 / 覆盖率 / 公共点击坐标 / 更新时间等写同目录 `info.json`），可随时整目录删除、下次分析按需重算。<b>单击任意一张图放大为原尺寸大图，再单击图片即还原</b>（按 Esc 也可关闭）。右栏信息精简为【分类标注】【匹配动作】【原始截图张数】【分辨率】四项（覆盖率改在各对照图标题与左侧列表展示）。样本 <2 张的分组不分析并明确提示。
- **坐标语义**：截图是窗口物理像素内容 → **图片像素 = 窗口相对坐标（Left, Top）**，后续 Act 阶段可直接据此执行鼠标动作。
- **数据布局**：图片与数据按“处理阶段”分三个目录（均相对程序运行目录，见 §四 目录结构）：
  - `capture/`：后台采集的**原始截图**（未标注，扁平存放，文件名 `IMG_yyyyMMdd_HHmmss.png`）；
  - `classify/`：**保存标注的瞬间**，PNG 连同图片同名标注 `IMG_x.png → IMG_x.json` 一起移入（数据与图同目录、扁平存放；分类标注记在 `json.state`，供后续 Classify/Act 直接取用）；
  - `summary/<分类标注>/`：汇总分析产物（七张对照图 + `info.json`，样本取自 `classify/` 同标注组，可随时重算）。
  标注 JSON 示例：

  ```json
  { "state": "登录页", "action": "click", "left": 640, "top": 360 }
  ```

  `action`：`none`（无动作，仅分类标注）/ `click`（携带 `left/top`）；未点击时落盘不写 `left/top`。（JSON 字段沿用 `state/action`，界面统一称「分类标注 / 匹配动作」）
- **API**：`GET /api/annotate/images`（列表含标注摘要）、`GET /api/annotate/image/{name}`（PNG）、
  `GET / PUT / DELETE /api/annotate/mark/{name}`（标注 CRUD，PUT 校验 action 取值与 click 坐标）、
  `POST /api/annotate/rename`（分类标注整体改名 `{from,to}`：批量更新该分类全部标注 json 的 `state`，并清掉旧 `summary/<旧分类标注>/` 产物目录；目标名已被其它分类使用或含非法文件名符号则 400）、
  `GET /api/app/meta`（版本探针：返回 `codeTs`=代码构建时间、`startedAt`=本次启动时间，前端据此检测服务端更新并自动刷新）、
  `GET /api/capture/status` / `POST /api/capture/pause` / `POST /api/capture/resume`（右上角「开启/暂停截图」：截图默认关闭，手动开启/暂停后台周期截图，不影响控制台）、
  `POST /api/system/shutdown`（控制台「退出程序」按钮调用：先响应页面，再结束截图任务并关闭整个服务）、
  `GET /api/annotate/think/groups`（分组清单：state+action、样本数、可分析性、是否已分析（七张产物齐全）、覆盖率、产物目录名 `dir`）、
  `POST /api/annotate/think/analyze`（异步分析所有「未分析或样本有变且 ≥2 张」的分组 → 重算七张对照图到 `summary/<dir>/` 并刷新 `info.json`）、
  `GET /api/annotate/think/task/{id}`（轮询分析任务进度）、
  `GET /api/annotate/think/img/{kind}?dir={dirB64}`（`kind = same | max | avg | m16 | a16 | m64 | a64`，分别对应交集图 / 多数图 / 均值图 / 1-16 多数图 / 1-16 均值图 / 1-64 多数图 / 1-64 均值图；`dirB64` = 分类标注产物目录名的「UTF-8 → Base64」，纯 ASCII 传输、不受容器字符集影响）、
  `POST /api/annotate/delete`（`{name: 截图文件名}`：把该 PNG 连同同名标注 `.json` 一起移入<b>系统回收站</b>（PowerShell `SendToRecycleBin` 软删除、可还原，控制台不再显示）；只允许删除本程序输出的 `img_*.png` 且写入已满 800ms，不存在则 404）。

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

### 3.3 配置项（application.properties，前缀 `capture.*` 与 `ui.*`）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `capture.window-keywords` | `MuMu模拟器,MuMu安卓设备,MuMu` | 目标图形程序窗口标题关键字（默认值即演示环境 MuMu 模拟器，改成你想操作的任意 GUI 程序标题即可），命中任一即候选，多窗口取面积最大 |
| `capture.interval-ms` | `3000` | 截图间隔（毫秒）；截图默认不开启，手动「开启截图」后按此间隔执行 |
| `capture.capture-dir` | `capture` | 原始截图（未标注）保存目录（相对运行目录，自动创建，已被 .gitignore 忽略） |
| `capture.classify-dir` | `classify` | 已标注截图 + 同名 `.json` 保存目录（扁平存放） |
| `capture.summary-dir` | `summary` | 汇总分析产物保存目录（内部按「分类标注」分目录，样本取 classify/，可随时重算） |
| `capture.output-dir` | `captures` | **旧版**单目录（截图/标注/汇总同根）：仅启动时自动迁移用，之后不再读写 |
| `capture.capture-timeout-ms` | `5000` | 传给采集器的内部抓帧超时（毫秒） |
| `capture.resize-width` | `1280` | 开启截图时把目标窗口**截图内容区（客户区）宽度**一次缩放为该物理像素值（高度按当前宽高比、位置不变；目标窗口最大化会自动还原后调整）。只在每次「开启截图」时执行一次；若当时窗口未开/最小化，截图任务开始后会自动补做。`0`=不调整 |
| `ui.auto-open` | `true` | 启动就绪后自动以 Edge/Chrome 应用窗口打开控制台网页（找不到则回退系统默认浏览器） |
| `ui.path` | `/annotate` | 自动打开的网页路径 |

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
   │  ├─ config/CaptureProperties.java       capture.* 配置项绑定
   │  ├─ config/StoragePaths.java            capture.* 目录配置求值：capture/ classify/ summary/ 三分区
   │  ├─ config/LegacyStorageMigrator.java   ApplicationRunner：旧版 captures/ 单目录启动时自动迁移到三分区
   │  ├─ ui/BrowserLauncher.java             ApplicationReadyEvent：自动以 Edge/Chrome 应用窗口打开控制台网页
   │  ├─ capture/
   │  │  ├─ WindowInfo.java                  窗口模型：句柄 / 标题 / 屏幕矩形 / 是否最小化
   │  │  ├─ WindowFinder.java                findTarget() 取"面积最大且优先非分层(WS_EX_LAYERED)"的窗口
   │  │  ├─ ScreenCaptureService.java        captureWindow() 调 WindowsCapture.exe；savePng() 原子落盘
   │  │  │                                   （.tmp→改名）并缓存 latestImage/latestFile
   │  │  ├─ WindowResizer.java               SetWindowPos：把客户区宽度缩放到 capture.resize-width（物理像素）
   │  │  ├─ CaptureControlController.java    /api/capture/status|pause|resume：开启/暂停周期截图（默认关闭）
   │  │  └─ WindowCaptureTask.java           ApplicationRunner + @Scheduled 周期截图（默认 paused=true 不截图，页面手动开启）
   │  └─ mark/
   │     ├─ CaptureMark.java                 标注模型：state(分类标注) / action(匹配动作 none|click) / left / top
   │     ├─ AnnotateController.java          REST /api/annotate/*：列图/PNG/标注 CRUD/rename/delete（列图跨 capture/ + classify/ 双根，读图片同名 .json）
   │     ├─ RecycleBin.java                  PowerShell SendToRecycleBin：把 PNG+同名标注移入系统回收站（可还原）
   │     ├─ AnnotatePageController.java      / 与 /annotate → 转发 static/annotate.html
   │     ├─ SystemController.java            POST /api/system/shutdown：退出程序（结束截图任务与服务）
   │     ├─ AppMetaController.java           GET /api/app/meta：版本探针（codeTs=代码构建时间，前端据此自动刷新）
   │     ├─ ThinkController.java             /api/annotate/think/*：groups / analyze / task / img
   │     └─ ThinkService.java                自动分组分析（产物落 summary/<分类标注>/：same|max|avg|maj16|avg16|maj64|avg64.png + info.json，单线程异步池，纯展示不参与标注）
   └─ resources/
      ├─ application.properties              全部可调配置
      ├─ static/annotate.html                标注单页（无外部 CDN，离线可用）
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
      ├─ maj16.png                           1/16 多数图（每样本按 16×16 块内覆盖率最多压缩后，再跨样本取覆盖率最多）
      ├─ avg16.png                           1/16 均值图（每样本按 16×16 块内 RGB 均值压缩后，再跨样本取均值）
      ├─ maj64.png                           1/64 多数图（同上，块为 64×64）
      ├─ avg64.png                           1/64 均值图（同上，块为 64×64）
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
2. 每 3 秒一张 PNG 持续占用磁盘：长跑注意清理 `capture/`（`classify/` 是要保留的样本；`summary/` 可随时重算后整目录删掉），或后续加"仅画面变化才存"。
3. 后续阶段（Match/Classify/Act）接入 `ImageMatcher` / `DecisionEngine` / `MouseController`。
