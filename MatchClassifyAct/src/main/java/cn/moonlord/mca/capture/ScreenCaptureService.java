package cn.moonlord.mca.capture;

import cn.moonlord.mca.config.CaptureProperties;
import cn.moonlord.mca.config.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 窗口截图服务：基于 Windows Graphics Capture (WGC)。
 *
 * <p>抓帧由本地采集器 WindowsCapture.exe 完成，Java 侧只负责调用并读取结果。</p>
 *
 * <p>WGC 直接抓取目标窗口的合成内容（含 GPU/DX 渲染表面），因此不需要窗口在前台 / 顶层 / 未被遮挡，
 * 不受显示器缩放比例影响（返回窗口自身的物理像素内容，天然无坐标偏移），被其它窗口完全盖住也能抓到画面。</p>
 *
 * <p>分层（WS_EX_LAYERED）窗口（如 MuMu 模拟器）无法被 WGC 的 CreateForWindow
 * 直接捕获，采集器会自动降级为"显示器捕获 + 按窗口矩形裁剪"，由 Java 侧透明调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenCaptureService {

    /** 文件名格式：IMG_日期_时间_毫秒.png，例如 IMG_20090312_211247_300.png（毫秒精度 + 同名避让，避免截图互相覆盖） */
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("'IMG_'yyyyMMdd_HHmmss_SSS");

    /** 采集器进程名（native 产物，随 jar 打包于 /native/win-x64/ 下） */
    private static final String HELPER_EXE = "WindowsCapture.exe";

    /** 采集器在 classpath 中的资源路径 */
    private static final String HELPER_RESOURCE = "/native/win-x64/" + HELPER_EXE;

    /** WindowsCapture 单次抓帧的总超时（毫秒），需大于其内部 --timeout-ms */
    private static final long HELPER_TIMEOUT_MS = 8000;

    // 采集器退出码语义，与 native/windowcap/windowcap.cpp 头注释保持一致；
    // Java 只需特判这几类可恢复 / 值得专门提示的状态，其余原样输出。
    private static final int EXIT_WINDOW_GONE = 20000;  // 找不到窗口 / 句柄无效
    private static final int EXIT_MINIMIZED = 20001;    // 最小化 / 无可捕获内容
    private static final int EXIT_TIMEOUT = 20004;      // 抓帧超时（含下方看门狗强制结束，统一此码）
    private static final int EXIT_WRITE_FAILED = 30008; // 输出文件写入失败

    private final CaptureProperties properties;
    private final StoragePaths storage;
    private final DedupCache dedupCache;

    private volatile Path helperExe;

    /** 正在运行的采集器进程；控制台点「退出程序」结束 JVM 时强制杀掉，避免残留抓帧进程 */
    private volatile Process activeCapture;

    /** 一条去重参考图：只记原图路径与尺寸（尺寸用于判断能否逐像素比对），不缓存像素数据 */
    private record Reference(Path path, int srcW, int srcH) {
    }

    /** 一次去重判定命中：{@code name} = 与之重复的那张已保存截图 / 标注样本的文件名（可直接向用户指出「和哪一张重复」）；
     *  {@code diffPercent} = 两者不一致像素点占比（%，两位舍入，必然 ≤ {@code threshold}）；
     *  {@code refState} = 参考图为 classify/ 已标注样本时的分类标注，capture/ 未标注参考图为 null。 */
    public record DuplicateMatch(String name, double diffPercent, double threshold, String refState) {
    }

    /** 一次去重扫描结果：{@code dup} 非空 = 命中重复；{@code minDiffPercent} = 扫描中与任一参考图的
     *  最小不一致像素点占比（-1 = 无可比参考），仅供「保存成功」提示用。 */
    public record DedupScan(DuplicateMatch dup, double minDiffPercent) {
    }

    /** 参考图旁 json 解析用（只取 state 归属）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 画面去重基准 = capture/（原始截图）与 classify/（已标注样本）两目录下的<b>全部</b> PNG，
     * key = 绝对路径，value 只含路径与尺寸（判定口径见 {@link #duplicateReference(BufferedImage, double)}）。
     *
     * <p>以磁盘全集为准而非「内存只保留最近 N 帧」：截图被标注移入 classify/、程序重启后仍能与历史任意一张对上。</p>
     */
    private final Map<String, Reference> referenceCache = new LinkedHashMap<>(256, 0.75f, true);

    /** 「启动历史重复清理 + 基准预热」完成的并发门闩：完成前截图去重判定等待，保证开启截图首轮即与清理后的全量参考比较 */
    private final CountDownLatch referenceSeeded = new CountDownLatch(1);

    /** 保护 {@link #referenceCache}：预热写入 / 判定读写 / 保存后注册共用 */
    private final Object referenceLock = new Object();

    /** 上次全量枚举时 capture/ 与 classify/ 两个基准目录的修改时间：目录文件集合（增/删/移入）未变则跳过磁盘全量枚举 */
    private long lastCapDirMtime = Long.MIN_VALUE;
    private long lastClsDirMtime = Long.MIN_VALUE;

    /**
     * 截取目标窗口当前画面（后台运行，不要求窗口在前台或顶层）。
     *
     * @param window 目标窗口
     * @return 窗口像素图；失败（窗口最小化/不可捕获/超时等）返回 null
     */
    public BufferedImage captureWindow(WindowInfo window) {
        if (window == null || window.getHwnd() == null) {
            return null;
        }
        if (window.isMinimized()) {
            log.debug("窗口 [{}] 已最小化，WGC 无可捕获内容，跳过", window.getTitle());
            return null;
        }

        Path bmp = null;
        try {
            bmp = Files.createTempFile("mca-window-", ".bmp");
            int rc = runWindowsCapture(window.getHwnd(), bmp);
            if (rc != 0) {
                logHelperFailure(rc, window.getTitle());
                return null;
            }
            BufferedImage image = ImageIO.read(bmp.toFile());
            if (image == null) {
                log.warn("WindowsCapture 输出的 BMP 无法解码: {}", bmp);
                return null;
            }
            log.debug("窗口 [{}] 抓帧成功: {}x{}", window.getTitle(), image.getWidth(), image.getHeight());
            return image;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("调用 WindowsCapture 截图失败: {}", e.getMessage());
            return null;
        } finally {
            if (bmp != null) {
                try {
                    Files.deleteIfExists(bmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /**
     * 调用 WindowsCapture.exe 抓取一帧并输出到指定 BMP。
     *
     * @return 采集器退出码（0=成功）
     */
    private int runWindowsCapture(WinDef.HWND hwnd, Path bmp) throws IOException, InterruptedException {
        Path exe = helper();
        long hwndValue = Pointer.nativeValue(hwnd.getPointer());
        List<String> command = List.of(
                exe.toString(),
                "--hwnd", Long.toString(hwndValue),
                "--out", bmp.toString(),
                "--timeout-ms", Long.toString(properties.getCaptureTimeoutMs()));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeCapture = process;
        try {
            boolean finished = process.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时帧输出不再读取：waitFor 超时后进程仍存活，先杀进程使其退出再收尾，避免 readAllBytes 等 EOF 永久阻塞
                process.destroyForcibly();
                process.waitFor();
                log.warn("WindowsCapture 执行超时，已强制结束");
                return EXIT_TIMEOUT;
            }
            byte[] outBytes = process.getInputStream().readAllBytes();
            String output = new String(outBytes, StandardCharsets.UTF_8);
            if (process.exitValue() == 0) {
                log.debug("WindowsCapture: {}", output.trim());
            } else {
                log.warn("WindowsCapture 返回 {}:\n{}", process.exitValue(), output.trim());
            }
            return process.exitValue();
        } finally {
            if (activeCapture == process) {
                activeCapture = null;
            }
        }
    }

    /**
     * JVM 退出钩子：控制台点「退出程序」走 System.exit 时会执行。
     * 若退出时恰好有一帧在抓，强制结束采集器，避免残留 WindowsCapture.exe。
     */
    @PostConstruct
    void registerExitHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::killActiveCapture, "capture-shutdown-hook"));
    }

    /** 最近一次启动历史重复清理的结果（有删除与无删除都记录），供控制台页一次性取走展示 */
    private volatile StartupDedupNotice startupDedupNotice = null;

    /** 启动去重清理结果摘要：at=完成时刻；threshold=本次差异阈值；scanned=参与比对截图数；
     *  removed=删除张数；costMs=实际耗时（毫秒，供页面提示「耗时 Xs/Xms」）；compared=真正逐像素比对的次数
     *  （尺寸不同的对不计）；reused=复用 {@link DedupCache} 已存结果、未再读像素的次数；
     *  minDiff=这些判定中最低的不一致像素点占比（无同尺寸可比对 = -1），供页面提示「最接近的一张还有多大差异」。 */
    public record StartupDedupNotice(long at, double threshold, int scanned, int removed, long costMs,
                                     int compared, int reused, double minDiff) {
    }

    /** 最近一次启动历史重复清理的结果（从未清理过 = null）。 */
    public StartupDedupNotice getStartupDedupNotice() {
        return startupDedupNotice;
    }

    /** 启动历史重复清理的「进行态」快照，供控制台页在清理期间显示一条一直刷新的进度提示（含已耗时）。
     *
     *  <p>at=开始时刻（毫秒，页面据此逐秒算已耗时）；done/total=已判定 / 待判定截图数（total=0 = 仍在枚举目录）；
     *  current=当前判定的文件名；compared/reused=已完成的逐像素比对 / 复用 {@link DedupCache} 省掉的次数；
     *  removed=已删张数；running 变 false 后页面撤掉进度提示，改由 {@link StartupDedupNotice} 展示结果。</p>
     */
    public record StartupDedupProgress(long at, int done, int total, String current,
                                       int compared, int reused, int removed, long costMs, boolean running) {
    }

    /** 最近一次（或正在进行中的）启动历史重复清理进度（从未启动 = null） */
    private volatile StartupDedupProgress startupDedupProgress = null;

    /** 启动历史重复清理的进行态快照（未启动 / 已完成后 running=false；从未启动过 = null）。 */
    public StartupDedupProgress getStartupDedupProgress() {
        return startupDedupProgress;
    }

    /**
     * 在独立后台线程执行「历史重复清理 → 去重基准预热」，由 {@link StartupDedupCleaner}
     * 在应用就绪（ApplicationReadyEvent，晚于全部 ApplicationRunner）后调用。
     * 任务不阻塞启动；截图去重判定会在开启截图前
     * 经 {@link #referenceSeeded} 等待本次任务完成，因此不会与清理阶段的文件删除并发。
     */
    void runStartupDedupAndSeedInBackground() {
        Thread t = new Thread(this::startupDedupAndSeed, "mca-startup-dedup");
        t.setDaemon(true);
        t.start();
    }

    private void startupDedupAndSeed() {
        try {
            // 启动历史重复清理：取两条路径启用阈值中的「最低要求」（两者都启用取较小者），
            // 某项 ≤0（该路径去重关闭）时以另一项为准，都 ≤0 则跳过清理
            double autoThreshold = properties.getDiffThresholdPercent();
            double manualThreshold = properties.getDiffThresholdManualPercent();
            double threshold = autoThreshold > 0 && manualThreshold > 0
                    ? Math.min(autoThreshold, manualThreshold)
                    : Math.max(autoThreshold, manualThreshold);
            if (threshold <= 0) {
                log.info("自动截图去重阈值 {}% 与手动保存去重阈值 {}% 均 ≤ 0：去重关闭，跳过启动历史重复清理与基准预热",
                        autoThreshold, manualThreshold);
                return;
            }
            synchronized (referenceLock) {
                long started = System.nanoTime();
                long startedAt = System.currentTimeMillis();
                // 先置进行态快照：页面据此在扫描期间显示一直刷新的进度（含已耗时）
                startupDedupProgress = new StartupDedupProgress(startedAt, 0, 0, "", 0, 0, 0, 0L, true);
                log.info("启动历史重复清理：开始检查 capture/ + classify/ 的全部历史截图重复"
                        + "（不一致像素点占比 ≤ 阈值 {}% 即视为重复删除，逐像素全尺寸比对；"
                        + "文件名 + 修改时间都没变过的组合直接复用上次的比对结果）", threshold);
                DedupResult result = dedupeHistoryLocked(threshold, startedAt);
                long costMs = (System.nanoTime() - started) / 1_000_000;
                int scanned = result.removed() + result.kept().size();
                // 收尾：running=false 让页面撤掉进度提示，结果由下方 notice 给出
                startupDedupProgress = new StartupDedupProgress(startedAt, scanned, scanned, "",
                        result.compared(), result.reused(), result.removed(), costMs, false);
                startupDedupNotice = new StartupDedupNotice(
                        System.currentTimeMillis(), threshold, scanned, result.removed(), costMs,
                        result.compared(), result.reused(), result.minDiff());
                // 判定量：比对 / 复用次数 + 其中最低的不一致占比（最接近重复的一对有多近）
                String cmpTxt = result.compared() + result.reused() > 0
                        ? String.format("，比对 %d 次%s，最低不一致像素点占比 %s%%", result.compared(),
                                result.reused() > 0 ? "（复用已存结果 " + result.reused() + " 次）" : "",
                                pctText(result.minDiff()))
                        : "，无可比对的同尺寸图";
                if (result.removed() > 0) {
                    log.info("启动历史重复清理：按不一致像素点占比 ≤ 阈值 {}% 判据检查 capture/ + classify/ 全部截图，删除重复 {} 张{}",
                            threshold, result.removed(), cmpTxt);
                } else {
                    log.info("启动历史重复清理：capture/ + classify/ 共 {} 张，未发现不一致像素点占比 ≤ {}% 的重复截图{}",
                            scanned, threshold, cmpTxt);
                }
                // 保留列表即最新基准全集：直接用它重建基准缓存，与运行期逐帧去重共用同一基准
                referenceCache.clear();
                for (Reference r : result.kept()) {
                    referenceCache.put(r.path.toString(), r);
                }
            }
        } catch (Throwable e) {
            StartupDedupProgress p = startupDedupProgress;
            if (p != null && p.running()) {   // 异常中断：把进行态收尾，避免页面进度提示一直挂着
                startupDedupProgress = new StartupDedupProgress(p.at(), p.done(), p.total(), p.current(),
                        p.compared(), p.reused(), p.removed(), System.currentTimeMillis() - p.at(), false);
            }
            log.warn("启动历史重复清理失败: {}", e.toString());
        } finally {
            dedupCache.saveIfDirty();   // 正常结束 / 异常中断都把已算出的比对结果落盘，下次不必重算同一对
            referenceSeeded.countDown();
        }
    }

    /** 强制结束正在运行的采集器进程 */
    void killActiveCapture() {
        Process p = activeCapture;
        if (p != null && p.isAlive()) {
            log.warn("退出前强制结束正在运行的 WindowsCapture 采集器");
            p.destroyForcibly();
        }
    }

    /**
     * 把 WindowsCapture.exe 从 classpath 解压到系统临时目录并缓存路径。
     * 采集器是静态链接的单文件 exe，解压一次即可长期使用。
     */
    private Path helper() throws IOException {
        Path cached = helperExe;
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        synchronized (this) {
            if (helperExe == null || !Files.exists(helperExe)) {
                helperExe = installHelper();
            }
            return helperExe;
        }
    }

    private Path installHelper() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "mca-windows-capture");
        Files.createDirectories(dir);
        Path target = dir.resolve(HELPER_EXE);
        try (InputStream in = getClass().getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath 缺少 " + HELPER_RESOURCE + "，请确认采集器已随 jar 打包");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("{} 已就绪: {}", HELPER_EXE, target);
        return target;
    }

    /** 把采集器退出码转成便于排障的日志 */
    private void logHelperFailure(int rc, String title) {
        switch (rc) {
            case EXIT_WINDOW_GONE -> log.warn("窗口 [{}] 已不存在，无法截图", title);
            case EXIT_MINIMIZED -> log.debug("窗口 [{}] 最小化或无可见内容，跳过", title);
            case EXIT_TIMEOUT -> log.warn("窗口 [{}] 抓帧超时", title);
            case EXIT_WRITE_FAILED -> log.warn("窗口 [{}] 截图文件写入失败（磁盘/权限?）", title);
            default -> log.warn("WindowsCapture 返回错误码 {}（窗口 [{}]），详情见上方 stderr", rc, title);
        }
    }

    /**
     * 把截图保存为 PNG 文件，并记录为最近一次截图（命名与窗口标题无关）。
     *
     * @param image  待保存的截图
     * @param window 截图来源窗口（仅用于调用方日志，不参与命名）
     * @return 保存后的文件路径
     */
    public Path savePng(BufferedImage image, WindowInfo window) throws IOException {
        Path directory = storage.capture();   // 原始截图固定写入 capture/
        Files.createDirectories(directory);

        // 毫秒时间戳命名；重名时追加 _2、_3…（与 classify/ 重名则标注列表以已标注版本为准，避免保存了却看不到）
        String base = LocalDateTime.now().format(FILE_TIME_FORMAT);
        String name = base + ".png";
        for (int i = 2; Files.exists(directory.resolve(name))
                || Files.exists(storage.classify().resolve(name)); i++) {
            name = base + "_" + i + ".png";
        }
        Path file = directory.resolve(name);

        // 先写 .tmp 再原子改名：控制台列表 / 浏览器不会看到写了一半的 PNG
        Path tmp = directory.resolve(name + ".png.tmp");
        boolean written = ImageIO.write(image, "png", tmp.toFile());
        if (!written) {
            Files.deleteIfExists(tmp);
            throw new IOException("ImageIO 不支持 png 格式");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }

        rememberReference(file, image.getWidth(), image.getHeight());   // 立即登记进去重基准，后续帧须与它也有足够差异
        return file;
    }

    /** 画面去重判定（自动截图循环在保存前调用）：阈值取 {@code capture.diff-threshold-percent}，
     *  判定口径同 {@link #duplicateReference(BufferedImage, double)}。 */
    public DuplicateMatch duplicateReference(BufferedImage image) {
        return duplicateReference(image, properties.getDiffThresholdPercent());
    }

    /** 带阈值的画面去重判定（手动采集、「存入分类 / 存到待标注」传 {@code capture.diff-threshold-manual-percent}）：
     *  与去重基准里每一张同尺寸 PNG 逐像素比对，不一致像素点占比 ≤ threshold 即判重复、须与每一张都
     *  &gt; threshold 才算新画面；阈值 ≤ 0 视为关闭、直接放行。返回 {@link #scanReference} 的 dup。 */
    public DuplicateMatch duplicateReference(BufferedImage image, double threshold) {
        return scanReference(image, threshold).dup();
    }

    /** 同上判定的一次扫描，额外带出「与任一参考图的最小不一致像素点占比」（{@code minDiffPercent}，-1 = 无可比参考）：
     *  供手动采集「保存成功」提示用（省掉保存后再全量扫一遍找最接近的图）；命中重复时提前返回，该值无意义。 */
    public DedupScan scanReference(BufferedImage image, double threshold) {
        if (image == null || threshold <= 0) {
            return new DedupScan(null, -1);   // 去重关闭：每次都保存
        }
        threshold = pct2(threshold);   // 统一口径：阈值也先四舍五入到两位小数，再与同口径的差异值比较
        try {
            referenceSeeded.await();   // 首轮须等基准载入完成，此后瞬时通过
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DedupScan(null, -1);   // 被中断：跳过去重放行保存（异常路径，尽量少影响截图）
        }
        int w = image.getWidth();
        int h = image.getHeight();
        synchronized (referenceLock) {
            scanAndLoadMissingLocked();   // 同步磁盘全集：新保存 / 移入 / 删除的参考即时生效
            if (referenceCache.isEmpty()) {
                return new DedupScan(null, -1);   // 还没有任何参考图：首帧保存，作为后续比较的基准
            }
            // 最近比对/保存过的排在前面：命中重复（回到过的画面、刚存的样本）更快
            List<Reference> refs = new ArrayList<>(referenceCache.values());
            Collections.reverse(refs);
            int compared = 0;      // 完成全尺寸逐像素判定的同尺寸参考数（命中即提前返回）
            double minDiff = -1;   // 已算出的最小不一致像素占比：调用方提示用，顺手取、不额外扫描
            for (Reference ref : refs) {
                if (ref.srcW != w || ref.srcH != h) {
                    continue;   // 历史窗口尺寸不同：无法逐像素对比，视为不重复
                }
                BufferedImage full = readFull(ref.path);
                if (full == null) {
                    continue;   // 解码失败：无法比对，视为不重复放行
                }
                compared++;
                double diff = mismatchPercentFull(image, full);
                if (minDiff < 0 || diff < minDiff) {
                    minDiff = diff;
                }
                if (diff <= threshold) {
                    log.debug("画面与已保存参考 {} 的不一致像素点占比 {}% ≤ 阈值 {}%，判为重复不保存",
                            ref.path.getFileName(), String.format("%.2f", diff), threshold);
                    return new DedupScan(new DuplicateMatch(ref.path.getFileName().toString(), diff, threshold,
                            refStateOf(ref.path)), diff);
                }
            }
            log.debug("画面与去重基准中 {} 张同尺寸参考图完成比对，全部不一致像素占比 > 阈值 {}%，判为新画面",
                    compared, threshold);
            return new DedupScan(null, minDiff);
        }
    }

    /** 读取一张 PNG 为全尺寸图；解码失败 / 为空返回 null（判重按「无法比对 = 不重复」放行） */
    private static BufferedImage readFull(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    /** 历史清理真的要比像素时才解码一张截图，并把「解码为空 / 读取失败」两种失败各提示一次；
     *  返回 null = 读不出来，调用方按「保留原文件不动」处理 */
    private static BufferedImage decodeScreenshot(Path p) {
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) {
                log.warn("启动去重清理：{} 解码为空，保留原文件不动", p.getFileName());
            }
            return img;
        } catch (IOException e) {
            log.warn("启动去重清理：读取 {} 失败，保留原文件不动: {}", p.getFileName(), e.getMessage());
            return null;
        }
    }

    /** 全尺寸逐像素「不一致像素点占比」：同尺寸时逐点比较 RGB（忽略 alpha，低 24 位不等即算不一致），
     *  返回不一致像素 / 总像素 × 100（两位舍入；与识别交集类的「完全一致」同判据，只是这里统计占比用于阈值比较）。
     *  任一图为空返回 0（无图可比）；尺寸不同返回 100（不可比，调用方按不重复放行）。 */
    private static double mismatchPercentFull(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            return 0;
        }
        int w = a.getWidth();
        int h = a.getHeight();
        if (w != b.getWidth() || h != b.getHeight()) {
            return 100;
        }
        int[] ap = a.getRGB(0, 0, w, h, null, 0, w);
        int[] bp = b.getRGB(0, 0, w, h, null, 0, w);
        int bad = 0;
        for (int i = 0; i < ap.length; i++) {
            if ((ap[i] & 0xffffff) != (bp[i] & 0xffffff)) {
                bad++;
            }
        }
        return pct2(bad * 100.0 / ap.length);
    }

    /**
     * 在 {@link #referenceLock} 内执行：重新枚举 capture/ + classify/ 顶层 PNG 作为基准集，
     * 清理目录中已删除文件的缓存项，并补齐缺失的参考（只读 PNG 头部拿尺寸、不解码像素）。
     */
    private void scanAndLoadMissingLocked() {
        // 基准目录文件集合（增/删/移入）未变时跳过全量枚举，静止画面每帧只做 2 次目录 stat；
        // 文件在库外被改名等目录 stat 不可靠的情况会退回 Long.MIN_VALUE，等价于每次都全扫（安全降级）
        long cM = dirLastModified(storage.capture());
        long lM = dirLastModified(storage.classify());
        if (referenceCache.isEmpty() || (cM == lastCapDirMtime && lM == lastClsDirMtime)) {
            lastCapDirMtime = cM;
            lastClsDirMtime = lM;
            return;
        }
        lastCapDirMtime = cM;
        lastClsDirMtime = lM;
        List<Path> disk = new ArrayList<>();
        collectPngs(storage.capture(), disk);
        collectPngs(storage.classify(), disk);
        Set<String> onDisk = new HashSet<>(disk.size() * 2);
        for (Path p : disk) {
            onDisk.add(p.toString());
        }
        referenceCache.keySet().removeIf(k -> !onDisk.contains(k));   // 已被删除/移出基准目录的不再参与
        for (Path p : disk) {
            String key = p.toString();
            if (referenceCache.containsKey(key)) {
                continue;
            }
            int[] dim = readDims(p);
            if (dim == null) {
                log.warn("去重参考图 {} 无法读取尺寸，跳过", p.getFileName());
                continue;
            }
            referenceCache.put(key, new Reference(p, dim[0], dim[1]));
        }
    }

    /** 目录最后修改时间（毫秒）；目录不存在或 stat 失败返回 MIN_VALUE（此时 mtime 守卫不生效、按每次都全扫处理） */
    private static long dirLastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    /** 把目录下所有 *.png 追加到 out（只扫顶层；截图与标注样本均扁平存放）；目录尚不存在则视为空 */
    private void collectPngs(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;   // 从未截图 / 从未标注：正常首启状态，不必告警
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .forEach(out::add);
        } catch (IOException e) {
            log.warn("枚举去重基准目录 {} 失败，跳过: {}", dir, e.getMessage());
        }
    }

    /* ---------------- 启动历史重复清理（把历史堆积的重复一次清掉） ---------------- */

    /** 历史清理结果：removed = 实际删除张数；kept = 保留全集（即清理后的基准，只含路径与尺寸）；
     *  compared / reused = 逐像素比对 / 复用 {@link DedupCache} 的次数；minDiff = 判定中最低的不一致像素点占比（无比对时 -1） */
    private record DedupResult(int removed, List<Reference> kept, int compared, int reused, double minDiff) {
    }

    /** 一张保留图的比较用状态：img 懒解码（整轮全命中缓存时一张都不用读），decodeTried 避免失败后反复重试 */
    private static final class Kept {

        private final Reference ref;
        private final DedupCache.Stamp stamp;
        private BufferedImage img;
        private boolean decodeTried;

        private Kept(Reference ref, DedupCache.Stamp stamp) {
            this.ref = ref;
            this.stamp = stamp;
        }

        /** 需要逐像素比对时才解码；解码失败返回 null（调用方按「这对不可比」跳过） */
        private BufferedImage image() {
            if (!decodeTried) {
                decodeTried = true;
                img = decodeScreenshot(ref.path());
            }
            return img;
        }
    }

    /**
     * 启动时对 capture/（未标注原始截图）+ classify/（已标注样本）的历史截图做一次重复清理
     * （须在 {@link #referenceLock} 内调用）：按保留优先级排序后逐张判定，每张只与前面已保留的图比较。
     *
     * <ul>
     *   <li>保留优先级：classify/ 已标注样本在前（有标注价值），capture/ 次之；同目录内较早的优先；</li>
     *   <li>判定口径与运行期完全一致（见 {@link #duplicateReference(BufferedImage, double)}），
     *       目的是清掉早期未开去重时堆积的重复；</li>
     *   <li>比对结果按「两张图的文件名 + 最后修改时间」记进 {@link DedupCache}：四元组没变过的组合下次
     *       启动直接复用（一个像素都不用读），因此全量重扫过一遍后重启几乎瞬时完成；缓存的是<b>差异值</b>
     *       而非「是否重复」，判定仍拿本次阈值去比，改阈值也不会用错结果；</li>
     *   <li>删除 classify/ 样本时连带删除同名 .json，中心表 data.json 的分类定义不动。</li>
     * </ul>
     *
     * @param threshold 差异阈值（不一致像素点百分比，&gt;0 才被调用）
     * @param startedAt 本次清理开始时刻（毫秒）：写进行态快照时原样带上，页面据此算「已耗时」
     * @return 删除张数、保留全集，以及判定量（比对次数 + 复用次数 + 其中最低的不一致像素点占比）
     */
    private DedupResult dedupeHistoryLocked(double threshold, long startedAt) {
        threshold = pct2(threshold);   // 与运行期判定同一口径：阈值两位舍入后再比较
        List<Path> all = new ArrayList<>();
        collectScreenshotPngs(storage.capture(), all);
        collectScreenshotPngs(storage.classify(), all);
        all.sort(Comparator
                .comparingInt((Path p) -> isUnder(p, storage.classify()) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString()));
        // 缓存身份 = 文件名 + 最后修改时间（毫秒）：先按本次全集裁掉已删除文件的条目（缓存不随历史无限
        // 增长），mtime 取不到的图（文件刚被删等）本次不参与缓存、照旧现算
        List<DedupCache.Stamp> stamps = new ArrayList<>(all.size());
        Set<String> identities = new HashSet<>(all.size() * 2);
        for (Path p : all) {
            DedupCache.Stamp s = DedupCache.stampOf(p);
            stamps.add(s);
            if (s != null) {
                identities.add(s.key());
            }
        }
        int dropped = dedupCache.retainOnly(identities);
        if (dropped > 0) {
            log.info("去重比对缓存裁掉 {} 条已删除文件的记录", dropped);
        }
        int removed = 0;
        int compared = 0;              // 本次真正逐像素比对次数（命中缓存的不算）
        int reused = 0;                // 直接复用缓存结果的次数（省掉的比对）
        double minDiff = -1;           // 这些判定（含复用）中最低的不一致像素点占比（-1 = 尚无判定）
        List<Kept> kept = new ArrayList<>();
        for (int idx = 0; idx < all.size(); idx++) {
            Path p = all.get(idx);
            // 每张判定前刷新进行态快照；写 volatile 对象的开销相对下面的解码 / 比对可忽略
            startupDedupProgress = new StartupDedupProgress(startedAt, idx + 1, all.size(),
                    p.getFileName().toString(), compared, reused, removed, 0L, true);
            DedupCache.Stamp stamp = stamps.get(idx);
            BufferedImage img = null;      // 只有真要逐像素比对时才解码
            boolean decodeFailed = false;
            Path dupOf = null;
            for (Kept k : kept) {
                double diff;
                Double cached = dedupCache.get(stamp, k.stamp);
                if (cached != null) {
                    diff = cached;    // 这对图以前算过、两侧文件都没变：直接复用
                    reused++;
                } else {
                    if (img == null && !decodeFailed) {
                        img = decodeScreenshot(p);
                        decodeFailed = img == null;
                    }
                    if (decodeFailed) {
                        break;   // 本张读不出来：保留原文件不动（与旧行为一致：不删除、也不作为后续的比较基准）
                    }
                    BufferedImage kimg = k.image();
                    if (kimg == null) {
                        continue;   // 保留图读不出来：这对无法比对，视为不重复
                    }
                    if (kimg.getWidth() != img.getWidth() || kimg.getHeight() != img.getHeight()) {
                        continue;   // 窗口尺寸不同：无法逐像素对比，视为不重复
                    }
                    diff = mismatchPercentFull(img, kimg);
                    compared++;
                    dedupCache.put(stamp, k.stamp, diff);
                }
                if (minDiff < 0 || diff < minDiff) {
                    minDiff = diff;
                }
                if (diff <= threshold) {
                    dupOf = k.ref.path();
                    break;
                }
            }
            if (dupOf != null) {
                if (deletePngWithJson(p)) {
                    removed++;
                    log.debug("启动去重清理：删除与 {} 不一致像素占比 ≤ {}% 的重复 {}", dupOf.getFileName(), threshold, p.getFileName());
                }
                continue;
            }
            if (decodeFailed) {
                continue;   // 本张读不出来：保留原文件不动，也不进保留集
            }
            int width;
            int height;
            if (img != null) {
                width = img.getWidth();
                height = img.getHeight();
            } else {
                int[] dim = readDims(p);   // 本次全命中缓存、没解码过像素：只读 PNG 头拿尺寸即可
                if (dim == null) {
                    log.warn("启动去重清理：{} 读不到尺寸，保留原文件但不作为比较基准", p.getFileName());
                    continue;
                }
                width = dim[0];
                height = dim[1];
            }
            kept.add(new Kept(new Reference(p, width, height), stamp));
        }
        List<Reference> keptRefs = new ArrayList<>(kept.size());
        for (Kept k : kept) {
            keptRefs.add(k.ref);
        }
        return new DedupResult(removed, keptRefs, compared, reused, minDiff);
    }

    /** 只收集 img_*.png（截图/标注样本的统一命名）；物理删除只针对此类文件，避免误伤目录里可能存在的其它 PNG */
    private void collectScreenshotPngs(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.startsWith("img_") && n.endsWith(".png");
                    })
                    .forEach(out::add);
        } catch (IOException e) {
            log.warn("枚举截图目录 {} 失败，跳过: {}", dir, e.getMessage());
        }
    }

    /** p 是否直接位于 dir 目录下（比较绝对父目录路径） */
    private static boolean isUnder(Path p, Path dir) {
        Path parent = p.getParent();
        return parent != null && parent.equals(dir.toAbsolutePath().normalize());
    }

    /** 删除一张截图 PNG；同目录存在同名 .json（分类归属标注）则一并删除，避免留下孤儿标注。png 删除成功返回 true */
    private boolean deletePngWithJson(Path png) {
        String name = png.getFileName().toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        try {
            Files.deleteIfExists(png.resolveSibling(base + ".json"));
        } catch (IOException e) {
            log.debug("启动去重清理：删除 {} 的同名标注文件失败: {}", png.getFileName(), e.toString());
        }
        try {
            Files.deleteIfExists(png);
            return true;
        } catch (IOException e) {
            log.warn("启动去重清理：删除 {} 失败: {}", png.getFileName(), e.toString());
            return false;
        }
    }

    /** 把刚保存成功的帧立即登记进去重基准（文件此刻已落盘，与下一轮从 capture/ 扫入等效，只存路径与尺寸） */
    private void rememberReference(Path file, int srcW, int srcH) {
        if (file == null || srcW <= 0 || srcH <= 0) {
            return;
        }
        synchronized (referenceLock) {
            referenceCache.put(file.toString(), new Reference(file, srcW, srcH));
        }
    }

    /** 只读图片头部尺寸（不解码像素），拿不到返回 null：注册参考图时避免为取宽高把整张图解出来 */
    private static int[] readDims(Path p) {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** 差异 / 阈值的统一舍入口径：四舍五入到小数点后 2 位（%）。判定、历史清理、对外提示都先经它，
     *  保证「拦截效果」与「提示文字」一致（不会出现显示成与阈值相等、却判为低于阈值的矛盾观感）。 */
    private static double pct2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 日志用百分比文本：整数直显（5.0 → "5"）、非整数保留两位并去尾零（0.5 → "0.5"），与页面提示同口径 */
    private static String pctText(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** 参考图为 classify/ 已标注样本时读旁 json 的分类标注；capture/ 或 json 缺失/无 state 返回 null */
    private String refStateOf(Path png) {
        if (png.getParent() == null || !png.getParent().equals(storage.classify())) {
            return null;
        }
        String fn = png.getFileName().toString();
        int p = fn.toLowerCase(Locale.ROOT).lastIndexOf(".png");
        Path json = png.resolveSibling((p >= 0 ? fn.substring(0, p) : fn) + ".json");
        try {
            String st = JSON.readTree(json.toFile()).path("state").asText(null);
            return (st == null || st.trim().isEmpty()) ? null : st.trim();
        } catch (IOException e) {
            return null;
        }
    }

}
