# MatchClassifyAct（MCA）

> **Match the pixels, Classify the state, Act accordingly.**
> 匹配像素 → 分类标注 → 执行动作。

Spring Boot 3.2.5 + JDK 17 + JNA 的 Windows **图形程序自动化**工程：对任意 GUI 窗口做「后台截图 → 人工给画面状态打标 → 实时识别当前画面属于哪个状态 → 按定义发送鼠标点击」。默认演示目标是 MuMu 模拟器内的安卓程序，改配置即可自动化任意窗口。

```
截图（窗口内容） → 分类标注（人工，控制台） → 对照图产物（汇总分析）
                                              ↓
                            执行模式：实时画面比对识别 → 鼠标点击
```

---

## 一、快速开始

前置：JDK 17 + Maven；须在**真实桌面会话**运行（无桌面环境抓不到窗口）。

```powershell
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/MatchClassifyAct-0.0.1-SNAPSHOT.jar
```

- 启动就绪后自动以 Edge/Chrome 应用窗口打开控制台（`http://localhost:8080/annotate`，`--ui.auto-open=false` 可关）；页面会持续探测后端，检测到代码更新后**自动刷新**加载新版。
- 截图**默认不开启**，需在页面右上角点「开启截图」；右上角「完全退出」结束服务并自动关页。
- 日常重启用 `restart.cmd`（停旧进程 → 清 `target\` → 重新打包 → 启动，日志在 `log\`）；`stop&clean.cmd` 只停并清构建输出。
- 数据都在运行目录下，启动自动创建；**可随时整目录拷贝/备份**。

## 二、目录与数据流

| 目录 | 内容 |
|------|------|
| `capture/` | 后台采集的**原始截图**（未标注，`IMG_yyyyMMdd_HHmmss.png`） |
| `classify/` | 保存标注时图从 capture/ 移入；图旁同名 json **只记归属** `{"state":…}`；`data.json` = **分类定义中心表**（每分类一份动作+坐标） |
| `summary/<分类标注>/` | **汇总分析产物**：14 张对照图（7 基础 + 7 `-unique` 独有区）+ `info.json`，仅供人工目检 / 执行识别，可随时整目录删除后重算 |

数据布局例子：

```json
// classify/data.json   —— 分类级统一定义（动作坐标只存这里）
{ "登录页": { "action": "click", "left": 640, "top": 360 } }
// classify/IMG_xxx.json —— 每张样本只写归属
{ "state": "登录页" }
```

旧版单目录 `captures/`（截图/标注/汇总混排）若存在，启动时自动迁移到上述三分区，之后不再读写。

---

## 三、控制台使用

左上角「工作模式」下拉在 **标注模式 / 执行模式** 间切换。

### 3.1 标注模式（打样本）

顶栏四段导航：**全部 / 未标注 / 已标注 / 汇总分析**。流程：看图 → 填分类标注 → 定匹配动作 → 保存并下一张。

- **分类标注** = 画面状态名（登录页 / 主界面 / 弹窗…）。它同时用作汇总产物目录名，故不能含 `\ / : * ? " < > |` 等文件名非法符号（输入时自动剔除）。chip 上的 ✎ 可整体改名（中心表 key 与全部样本归属一并更新，旧产物目录自动清理）。
- **匹配动作** = 无动作 / 鼠标点击（在图上点一下即记坐标）。每个分类只有一份定义：**首次**打标时确定，之后同分类加样本直接填分类保存即可，坐标自动沿用；在已标注图上改动作/坐标 = **重定义该分类**（全组同步）。唯一动作只约束**仍在使用（有样本）**的分类；样本删光后中心表残留的空定义视为全新分类，下次首标会直接重新定义，不会挡你新建。
- 保存后自动带出上次标记并跳到未标注图，快捷键 `↑`/`↓` 切图、`Enter` 保存，可连续快速打标。
- 「已标注」视图里点标签右侧的数字 = 只浏览该分类的已标注图并跳到第一张（再次点击同一数字取消过滤），不会跳去「全部」。
- 左侧列表排序：全部、已标注按时间倒序（最新在上）；未标注按时间正序（最旧在上），从最早的开始逐张往下打标。
- **图片像素 = 窗口坐标**（截图即窗口内容，resize 已逐像素对齐），坐标语义一直沿用到执行点击。

### 3.2 后台截图与去重

- 页面「开启截图」后按 `capture.interval-ms`（默认 1s）周期后台截图，节拍是 **fixedDelay**：处理完一帧再等这么久取下一帧，绝不叠帧。
- 每帧保存前与历史全部 PNG（capture/ + classify/）比对，平均差异小于 `capture.diff-threshold-percent`（默认 3%）即视为重复丢弃；结果以单条轻提示展示在右下角（保存成功 / 与哪张重复）。
- **每次启动**还会在后台自动清理一遍历史重复（按自动 / 手动两个去重阈值中较低者，默认 0.3%），把早期堆积的重复截图删掉；画面持续变化产生的新重复仍靠运行期去重挡。
- 截图目标尺寸不符 `capture.resize-width × resize-height`（默认 1280×720）时，自动用 `SetWindowPos` 缩放窗口后重截验证，直到 PNG 恰好达标才保存。

### 3.3 汇总分析（生成对照图）

进入「汇总分析」段会自动异步分析每个「样本 ≥1 张且尚未分析」的分组；样本有变也会自动标记待重算。产物 = **14 张对照图**（7 张基础合成图 + 每张对应的 `-unique` 独有区图）+ `info.json`（样本数 / 覆盖率 / 公共点击坐标等），**供人工目检，并作为执行模式实时识别 / 智能分析建议的比对源——只读使用，不自动改动任何标注**：

| 产物 | 含义 |
|------|------|
| `same.png` 交集图 | 每像素取「覆盖率 >90%」的主流颜色，不足则透明 = 样本间公共（稳定）区 |
| `major.png` 多数图 / `avg.png` 均值图 | 每像素取出现最多的颜色 / 全部样本 RGB 平均 |
| `major8.png` `avg8.png` `major32.png` `avg32.png` | 8×8、32×32 块内「多数色 / 均值色」的降采样合成 |
| 7 张 `-unique` 独有区图（`same-unique` / `major-unique` / `avg-unique` / `major8-unique` / `avg8-unique` / `major32-unique` / `avg32-unique`） | 每张对应一种基础图：在**该 kind 基础图**上去掉「**其它分类同 kind 基础图**同位置同色」的像素，剩本分类独有区域，用来观察相近分类差在哪。均为跨分类产物：**要等全部分组的 7 张基础图都生成完才开始算**；**必须 14 张齐全**该分组才参与执行识别——基础图看整幅画面、独有区图只盯本分类独占区，两者互补拉开相近分类的差距。接口 kind 与文件名一致（块图无短码，kind 即 `major8/avg8/major32/avg32`），仅多数图 kind=`max` 对应磁盘名 `major` |

已分析分组在列表中按交集图覆盖率**从低到高**排列：覆盖率越低 = 采到了该状态不同时刻的真实差异（样本更充分），排在前面；覆盖率越高 = 多为同一画面反复截取（采样不足），排在后面提示补采不同状态的画面。未分析 / 待重算分组排在其后（其中样本多的靠前）。单击任一图放大查看，Esc 还原。

### 3.4 执行模式（识别 + 点击）

切换过去即自动截图识别一轮并展示画面与结果，此后页面每 10 秒后台同步一次最新结果；点「立即识别」可手动单轮刷新。「开启自动识别」= 自动循环：每轮「截图识别 → 结果停留 3 秒供查看（可点按钮停止）→ 按下方所选方式执行点击 → 等 3 秒游戏响应」再进下一轮，运行中再次点击即停止。截图复用标注模式的找窗/调窗机制，**只用于识别与展示、不写盘**。

- **识别口径**：把当前帧与 `summary/` 里各分组 **14 张对照图**（7 基础 + 7 -unique 独有区图，需齐全才参与）逐张比对（全幅图每隔 4 像素取 1 点抽样）。逐点判据按「代表色来源」分两套：**交集/多数类**（交集图 same / 多数图 major / 8×8·32×32 多数块图 major8·major32，及各自 -unique，共 8 张）颜色是样本真实像素，要求**逐像素完全一致**（R/G/B 三通道差都为 0 才判「匹配」，画面同状态因 resize 逐像素对齐应能精确重现）；**均值类**（均值图 avg / 8×8·32×32 均值块图 avg8·avg32，及各自 -unique，共 6 张）颜色是样本平均色（真实画面不会恰好等于它），用逐通道容差（三通道差都 ≤ `execute.rgb-dist-threshold`（默认 255/3=85）判「匹配」，任一通道 > 它判「不匹配点」）。分类差异度 = 各图「不匹配点占比」的**加权平均** = **(独有交集图×50 + 交集图×30 + 其余 12 张图平均×20) ÷ 100**（交集/独有交集锁定样本的公共稳定区与独占核心区，权重最高；其余图合计只占 20%，个别维明显差异不会被过度放大）；识别以最近似分组为准、**不设识别阈值门槛**（差异度仅作相近程度展示，不再区分「已识别/未识别」）。
- **依赖前提**：目标画面需先在标注模式打好标并生成汇总产物（产物缺失或尺寸不符的分组不参与识别）。
- **执行动作**：识别出「鼠标点击」动作后点「执行动作」，直接按右侧识别结果的坐标发送点击，**不再重新截图识别**（画面已变化请先点「立即识别」刷新结果）：
  - `post`（默认，后台消息）：向目标窗口投递完整点击消息序列，不要求窗口前台/可见、不抢占鼠标（游戏 / 模拟器多忽略合成消息，此时应切 `screen`）；
  - `screen`（前台点击）：`SetForegroundWindow + SetCursorPos + mouse_event` 真实输入（要求窗口可见不被遮挡）。
- 画面区实时帧上用**红白准星**叠加标出识别命中点（点击坐标），右栏展示识别结果与候选分类（差异度由小到大）。

---

## 四、配置

业务可调项默认值**固化在代码**（`capture.*` → `CaptureProperties`、`execute.*` → `ExecuteProperties`、`ui.*` → `BrowserLauncher`），用 `application.properties`、环境变量或启动参数（`--键=值`）覆盖即可，**无需改代码**：

| 键 | 默认值 | 说明 |
|------|--------|------|
| `capture.window-keywords` | `MuMu模拟器,MuMu安卓设备,MuMu` | 目标窗口标题关键字（逗号分隔），命中任一即候选、取面积最大 |
| `capture.interval-ms` | `1000` | 相邻两帧截图的最小间隔（fixedDelay，处理完一帧再等这么久） |
| `capture.resize-width` `capture.resize-height` | `1280` `720` | 强制截图目标尺寸，不符自动调窗重截；任一设 `0` = 关闭尺寸校验（此时需目标程序内分辨率与此一致，否则画面可能拉伸） |
| `capture.capture-timeout-ms` | `5000` | 采集器内部抓帧超时 |
| `capture.capture-dir` `classify-dir` `summary-dir` | `capture` `classify` `summary` | 三分区目录名（相对运行目录） |
| `capture.output-dir` | `captures` | 旧版单目录，仅启动迁移用 |
| `capture.diff-threshold-percent` | `3` | 自动截图去重阈值（%）：与历史任一 PNG 平均差异低于它即判重复不保存；`0` = 关闭 |
| `capture.diff-threshold-manual-percent` | `0.3` | 手动另存（执行页把当前画面存为待标注）去重阈值；`0` = 关闭 |
| `ui.auto-open` | `true` | 启动后自动打开控制台应用窗口（找不到 Edge/Chrome 则回退系统浏览器） |
| `ui.path` | `/annotate` | 自动打开的页面路径 |
| `ui.window-size` `ui.center` | `1760x990` `true` | 控制台窗口尺寸 / 是否居中 |
| `execute.interval-ms` | `2000` | 后端「执行循环」接口（`POST /api/execute/start`）的周期截图识别间隔；当前控制台页由「立即识别 / 开启自动识别」驱动，不使用该周期 |
| `execute.match-threshold-percent` | `25` | 差异度参考阈值：识别不设判定门槛，仅随识别结果返回供界面参考 |
| `execute.rgb-dist-threshold` | `85`（255/3） | **均值型对照图**（avg/avg8/avg32 及各自 -unique）判不匹配的逐通道色差上限：R/G/B 任一通道差 > 它判「不匹配」，三通道都 ≤ 它才匹配；交集/多数型（same/major/major8/major32 及各自 -unique）固定「逐像素完全一致」，不受它影响 |
| `execute.click-mode` | `post` | `post`=后台消息点击；`screen`=真实前台点击 |

## 五、代码 / 目录结构

```
MatchClassifyAct/
├─ native/windowcap/windowcap.cpp      WGC 采集器（C++/WinRT，含分层降级）；build.bat 编译
├─ src/main/resources/native/win-x64/  WindowsCapture.exe（随 jar 打包，运行时解压到 %TEMP%）
└─ src/main/java/cn/moonlord/mca/
   ├─ MatchClassifyActApplication      入口（非 headless + Per-Monitor DPI + @EnableScheduling）
   ├─ config/                          CaptureProperties / ExecuteProperties / StoragePaths /
   │                                   WebServerConfig(UTF-8) / LegacyStorageMigrator(旧目录迁移)
   ├─ ui/BrowserLauncher               启动后自动开控制台网页
   ├─ capture/                         截图层：WindowFinder（找窗）→ ScreenCaptureService
   │                                   （调采集器、原子落盘、去重）→ WindowResizer（尺寸不符
   │                                   调窗重截）；WindowCaptureTask 周期任务 + StartupDedupCleaner
   ├─ act/                             执行层：ExecuteController（REST）→ ExecutionService（截图+识别主轴）
   │                                   → FrameClassifier（与 summary 对照图比对识别）→ WindowClicker
   └─ mark/                            标注层：ClassifyStore（中心表）→ AnnotateController /
                                        ThinkController（汇总分析）/ AppMetaController / SystemController
src/main/resources/static/                控制台前端（标注 / 执行双模式，无外部依赖）
  ├─ index.html   页面骨架
  ├─ annotate.css 样式
  └─ annotate.js  交互脚本
```

## 六、已知限制 & 待办

- **抓窗限制**：WGC 无法直接抓 `WS_EX_LAYERED` 分层窗口（返回 `E_INVALIDARG`，业界通病）；仅当所有候选窗口都是分层窗时才降级为「显示器捕获 + 窗口矩形裁剪」——此时被遮挡部分会截到遮挡内容、移出屏幕部分被裁掉。
- 目标窗口在**其它虚拟桌面**时抓不到（API 限制）。
- 控制台输出中文乱码多为终端 GBK 显示问题（程序内部均 UTF-8）：先 `[Console]::OutputEncoding=[Text.Encoding]::UTF8` 再看日志。
- 多开多个相同目标程序时按标题取「面积最大」，暂无法指定具体实例。
- `capture/` 持续落盘会积累：去重可挡静止/重复画面，长跑建议定期清理（`classify/` 是要保留的样本；`summary/` 可随时删除重算）。
- 识别对轻微光照 / 色偏 / 动态区域敏感，动态画面较多的分类需多采样本覆盖（后续方向：局部 ROI / 特征匹配）。
- 自动点击通过「开启自动识别」进行（每轮留 3 秒可停止的确认窗口）；尚无可配置的策略链（失败重试 / 条件分支等）与执行历史记录。

## 附录

### A. REST API 摘要

- 标注：`GET /api/annotate/images`（列图）、`image/{name}`（PNG）、`defs`（分类定义表）、`PUT/DELETE /api/annotate/mark/{name}`、`POST /api/annotate/rename`（分类整体改名）、`POST /api/annotate/delete`（移入系统回收站）。
- 汇总分析：`GET /api/annotate/think/groups`、`POST /api/annotate/think/analyze`、`POST …/rebuild`（一键重建全部产物）、`GET …/task/{id}`、`GET …/img/{kind}?dir={dirB64}`（`kind = 14 图之一：same|same-unique|max|max-unique|avg|avg-unique|major8|major8-unique|avg8|avg8-unique|major32|major32-unique|avg32|avg32-unique`，`dir` 为分类目录名 UTF-8 → Base64）。
- 截图：`GET /api/capture/status`、`POST /api/capture/pause|resume`。
- 执行：`GET /api/execute/status`、`POST …/start|stop|refresh|act`、`GET …/latest|frame`。
- 其它：`GET /api/app/meta`（版本探针 `codeTs`/最近截图结果）、`POST /api/system/shutdown`。

### B. 截图采集技术（为什么用 WGC）

需求是「**后台** + 抓 **GPU/DX 合成内容** + 多显示器缩放下坐标不偏移」地抓指定窗口——只有微软的 **Windows Graphics Capture**（WGC）满足（`Robot` 要窗口在前台、会截到遮挡层、缩放会偏；`PrintWindow` 对 GPU 分层窗口实测全黑）。因此用 C++/WinRT 写了个单文件采集器 `WindowsCapture.exe`（静态链接，与编译环境无关），Java 用 `ProcessBuilder` 调它：

```
WindowsCapture.exe --hwnd <句柄> --out <输出.bmp> [--timeout-ms 毫秒]
```

退出码分段：`0` 成功；`1xxxx` 参数错误；`2xxxx` 找不到窗口 / 分层 / 抓帧超时等窗口问题；`3xxxx` 内部异常。完整码表见 `native/windowcap/windowcap.cpp` 文件头注释（输出均为 UTF-8）。
