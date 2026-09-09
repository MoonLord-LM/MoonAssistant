package cn.moonlord.mca.act;

import cn.moonlord.mca.config.StoragePaths;
import cn.moonlord.mca.mark.CaptureMark;
import cn.moonlord.mca.mark.ClassifyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 特征验证：对每一种「汇总图算法」（kind 产物，与识别参与比对的全套 kind 一致）做自回归评分。
 *
 * <p>按某一种算法生成了每个分类的汇总图后，把 classify/ 的全部已标注原图逐张与【全部分类】的
 * 同 kind 汇总图按识别同口径逐点比对（复用 {@link FrameClassifier} 的产物/原图缓存与比对桥接），
 * 统计两个评价指标：
 * <ul>
 * <li>A：每个原图与「自己分类的该算法生成图」比对的不匹配像素占比，取全部原图的平均值
 * （越低越好，表明样本比较集中）；</li>
 * <li>B：每个原图在全部同类生成图里做匹配、最佳值刚好是自己分类的比例，取全部原图的平均值
 * （越高越好，表明算法区分度较好）。</li>
 * </ul>
 * 按算法逐 kind 独立计算、完成后按「当前样本/产物指纹」缓存结果并标记是否过期（样本或产物有
 * 改动后需重新验证）。独立后台任务线程跑，结果只存内存不落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    private final StoragePaths storage;
    private final ClassifyStore classifyStore;
    private final FrameClassifier classifier;

    /** 单线程后台执行器（任务内部的逐样本比对用并行流加速）。 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "verify");
        t.setDaemon(true);
        return t;
    });

    /** 每 kind 的验证结果（key = kind，与验证维度的全部 kind 一致；过期旧值保留便于对照）。 */
    private final Map<String, KindStat> results = new ConcurrentHashMap<>();

    /** 正在跑 / 刚跑完的任务进度（volatile 快照式，每次轮询读取）。 */
    private volatile Run run;

    /** 指纹缓存（3 秒 TTL，避免每次轮询全量 stat 产物/样本）。 */
    private volatile long fpAt;
    private volatile String fpLast;

    /** 一个 kind 的完整验证结果。 */
    public static class KindStat {
        public final String kind;
        public final String fp;
        public final long doneMs;
        public final int costMs;
        public final Double a;
        public final Double b;
        public final int samples;
        public final int groups;
        public final List<Map<String, Object>> rows;

        public KindStat(String kind, String fp, long doneMs, int costMs,
                        Double a, Double b, int samples, int groups,
                        List<Map<String, Object>> rows) {
            this.kind = kind;
            this.fp = fp;
            this.doneMs = doneMs;
            this.costMs = costMs;
            this.a = a;
            this.b = b;
            this.samples = samples;
            this.groups = groups;
            this.rows = rows;
        }
    }

    /** 进度快照。 */
    public static class Run {
        public volatile boolean running = true;
        public volatile boolean finished;
        public volatile String error;
        public volatile int done;
        public final int total;
        public volatile String cur;
        public volatile int processed;
        public volatile int totalSamples;
        public final long startedMs;
        public volatile long endedMs;   // 结束时刻（finished 置位时记录；0 = 尚未结束）

        Run(String fp, int total) {
            this.total = total;
            this.startedMs = System.currentTimeMillis();
        }
    }

    /** summary/ 下的一个有效分类目录（产物目录 = 分类标注）。 */
    private record Ctx(int idx, String state, String dir, int w, int h,
                       String action, Integer clickLeft, Integer clickTop) {
    }

    /** classify/ 下的一张已标注原图（归属某分类目录）。 */
    private record Smp(Path png, Ctx own) {
    }

    /** 某 kind 计算时的参与目录（产物已成功解码）。 */
    private static final class Cand {
        final Ctx ctx;
        final FrameClassifier.CachedPx art;

        Cand(Ctx ctx, FrameClassifier.CachedPx art) {
            this.ctx = ctx;
            this.art = art;
        }
    }

    // ---------------------------------------------------------------- 对外

    /** 是否正在计算（未完成的任务才算 running）。 */
    public boolean running() {
        Run r = run;
        return r != null && r.running && !r.finished;
    }

    /** 当前任务进度（无任务时 null）。 */
    public Run currentRun() {
        return run;
    }

    /** 启动一次完整验证（计算全部 kind）；已有任务在跑时拒绝并返回 false。 */
    public synchronized boolean start() {
        if (running()) {
            return false;
        }
        String fp = fpNow();
        List<String> order = classifier.verifyOrder();
        Run r = new Run(fp, order.size());
        run = r;
        exec.submit(() -> doRun(r, fp, order));
        return true;
    }

    private void doRun(Run r, String fp, List<String> order) {
        // 验证会逐 kind 解码全部参与分类的产物像素：先清空识别/汇总阶段累积的常驻像素缓存腾出堆空间
        // （验证走不写缓存的按需解码、每 kind 用后即释放；执行模式后续首次识别会自动重新解码补齐），
        // 否则 81 组 × 各 kind 全幅产物叠加会直接把堆撑爆
        classifier.clearPxCaches();
        List<Ctx> groups = new ArrayList<>();
        List<Smp> samples = new ArrayList<>();
        try {
            groups = scanGroups();
            samples = scanSamples(groups);
        } catch (Exception e) {
            r.error = "准备数据失败：" + e;
            r.finished = true;
            r.endedMs = System.currentTimeMillis();
            r.running = false;
            log.warn("特征验证准备失败: {}", e.toString());
            return;
        }
        // 每张样本的画面块压缩序列（FrameWork）整轮只建一次、38 种 kind 复用（样本自身全幅像素与产物
        // 复用 FrameClassifier 缓存，这里只多放每样本一份小体积的 8/32 块序列）
        Map<String, FrameClassifier.FrameWork> works = new ConcurrentHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            String kind = order.get(i);
            r.cur = kind;
            r.done = i;
            try {
                KindStat st = computeKind(kind, fp, groups, samples, works);
                results.put(kind, st);
            } catch (Throwable e) {
                r.error = "验证 kind=" + kind + " 失败：" + e;
                log.warn("特征验证 kind={} 失败: {}", kind, e.toString());
                break;
            }
            r.done = i + 1;
        }
        r.finished = true;
        r.endedMs = System.currentTimeMillis();
        r.running = false;
    }

    /** 结果 / 总览（供前端轮询）：任务进度 + 全部 kind 的评分与状态。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        String fp = fp();
        out.put("running", running());
        out.put("samples", classifyStore.listClassifiedPngs().size());
        out.put("groups", scanGroups().size());
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String kind : classifier.verifyOrder()) {
            KindStat st = results.get(kind);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", kind);
            if (st == null) {
                k.put("state", "none");
                k.put("a", null);
                k.put("b", null);
                k.put("samples", 0);
            } else {
                boolean fresh = fp.equals(st.fp);
                k.put("state", fresh ? "done" : "stale");
                k.put("a", st.a);
                k.put("b", st.b);
                k.put("samples", st.samples);
            }
            kinds.add(k);
        }
        out.put("kinds", kinds);
        Run r = run;
        if (r != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("finished", r.finished);
            if (r.finished && r.endedMs > 0) {
                task.put("costMs", Math.max(0, r.endedMs - r.startedMs));
            }
            task.put("error", r.error);
            task.put("done", r.done);
            task.put("total", r.total);
            task.put("cur", r.cur);
            task.put("processed", r.processed);
            task.put("totalSamples", r.totalSamples);
            out.put("task", task);
        }
        return out;
    }

    /** 某 kind 的分类级明细（未计算返回 null；fresh 表示与当前样本/产物一致）。 */
    public Map<String, Object> detail(String kind) {
        if (kind == null || !classifier.verifyOrder().contains(kind)) {
            return null;
        }
        KindStat st = results.get(kind);
        if (st == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("fresh", fp().equals(st.fp));
        out.put("doneMs", st.doneMs);
        out.put("costMs", st.costMs);
        out.put("a", st.a);
        out.put("b", st.b);
        out.put("samples", st.samples);
        out.put("groups", st.groups);
        out.put("rows", st.rows);
        return out;
    }

    // ---------------------------------------------------------------- 指纹

    /** 最新指纹（3 秒缓存）。 */
    private String fp() {
        long now = System.currentTimeMillis();
        String v = fpLast;
        if (v != null && now - fpAt < 3000) {
            return v;
        }
        return fpNow();
    }

    /** 样本 / 产物变动指纹：classify 原图与标注 json、summary 产物（png + info.json）的 名称+大小+修改时间。 */
    private synchronized String fpNow() {
        StringBuilder sb = new StringBuilder();
        appendTreeFiles(storage.classify(), sb);
        appendTreeFiles(storage.summary(), sb);
        String s = sb.toString();
        fpAt = System.currentTimeMillis();
        fpLast = Integer.toHexString(s.hashCode());
        return fpLast;
    }

    private void appendTreeFiles(Path root, StringBuilder sb) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(root)) {
            dirs.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        files.sort((a, b) -> a.toString().compareTo(b.toString()));
        for (Path p : files) {
            try {
                BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
                sb.append(p.toString()).append('|').append(at.size()).append('|')
                  .append(at.lastModifiedTime().toMillis()).append('\n');
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 计算

    /** 枚举 summary/ 有效分类目录（有 state 且尺寸有效），按目录名排序。 */
    private List<Ctx> scanGroups() {
        List<Ctx> out = new ArrayList<>();
        Path sum = storage.summary();
        if (!Files.isDirectory(sum)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> s = Files.list(sum)) {
            dirs = s.filter(Files::isDirectory).sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return out;
        }
        int idx = 0;
        for (Path dir : dirs) {
            Map<String, Object> info = classifier.verifyInfo(dir);
            Object sv = info.get("state");
            String state = sv == null ? "" : String.valueOf(sv).trim();
            Integer w = intOf(info.get("width"));
            Integer h = intOf(info.get("height"));
            if (state.isEmpty() || w == null || h == null || w <= 0 || h <= 0) {
                continue;
            }
            Object av = info.get("action");
            String action = av == null ? CaptureMark.ACTION_NONE : String.valueOf(av);
            if (action == null || action.isEmpty()) {
                action = CaptureMark.ACTION_NONE;
            }
            out.add(new Ctx(idx++, state, dir.toString(), w, h, action,
                    intOf(info.get("clickLeft")), intOf(info.get("clickTop"))));
        }
        return out;
    }

    /** 枚举 classify/ 原图并归属到其 state 对应的分类目录（无对应目录的原图不参与验证）。 */
    private List<Smp> scanSamples(List<Ctx> groups) {
        Map<String, Ctx> byState = new LinkedHashMap<>();
        for (Ctx c : groups) {
            byState.putIfAbsent(c.state(), c);
        }
        List<Smp> out = new ArrayList<>();
        for (Path png : classifyStore.listClassifiedPngs()) {
            CaptureMark m = classifyStore.sampleOf(png);
            if (m == null) {
                continue;
            }
            String st = m.getState() == null ? "" : m.getState().trim();
            Ctx own = st.isEmpty() ? null : byState.get(st);
            if (own != null) {
                out.add(new Smp(png, own));
            }
        }
        return out;
    }

    /** 算一个 kind 的 A / B 与分类级明细。works = 本验证轮共享的每样本画面块压缩缓存（键 = 原图路径）。 */
    private KindStat computeKind(String kind, String fp, List<Ctx> groups, List<Smp> samples,
                                 Map<String, FrameClassifier.FrameWork> works) {
        long t0 = System.currentTimeMillis();
        String file = classifier.verifyFile(kind);
        boolean clickKind = kind.startsWith("click");

        // 该 kind 的参与目录：产物存在（点击类还需点击动作与坐标），并成功解码
        List<Cand> cands = new ArrayList<>();
        for (Ctx c : groups) {
            if (clickKind) {
                if (!CaptureMark.ACTION_CLICK.equals(c.action()) || c.clickLeft() == null || c.clickTop() == null) {
                    continue;
                }
            }
            Path f = Path.of(c.dir(), file);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            FrameClassifier.CachedPx art = classifier.verifyArtifact(f, kind);
            if (art != null) {
                cands.add(new Cand(c, art));
            }
        }
        if (cands.isEmpty()) {
            return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                    null, null, 0, 0, List.of());
        }

        // 目录下标 → 参与目录下标（用于定位样本的“自分类”产物）
        Map<Integer, Integer> posOf = new LinkedHashMap<>();
        for (int j = 0; j < cands.size(); j++) {
            posOf.put(cands.get(j).ctx.idx(), j);
        }

        // 该 kind 的样本群：归属目录参与了本 kind 且能定位到自分类产物
        List<Smp> pop = samples.stream().filter(s -> posOf.containsKey(s.own().idx())).toList();
        int n = pop.size();
        double[] selfScore = new double[n];
        double[] bestScore = new double[n];
        for (int i = 0; i < n; i++) {
            selfScore[i] = -1;
            bestScore[i] = Double.POSITIVE_INFINITY;
        }
        double[] selfSum = new double[cands.size()];
        int[] cnt = new int[cands.size()];
        int[] hit = new int[cands.size()];
        int[] selfOf = new int[n];
        for (int i = 0; i < n; i++) {
            selfOf[i] = posOf.get(pop.get(i).own().idx());
        }

        Run r = run;
        if (r != null) {
            r.processed = 0;
            r.totalSamples = n;
        }
        // 逐样本并行：与全部分类同 kind 汇总图逐点比对（识别同口径）
        Stream.iterate(0, i -> i + 1).limit(n).parallel().forEach(i -> {
            Smp smp = pop.get(i);
            Ctx own = smp.own();
            String wkey = smp.png().toString();
            FrameClassifier.FrameWork work = works.get(wkey);
            if (work == null) {
                FrameClassifier.CachedPx raw = classifier.verifySample(smp.png(), own.w(), own.h());
                if (raw == null) {
                    return;
                }
                FrameClassifier.FrameWork built = new FrameClassifier.FrameWork(raw.px, raw.w, raw.h);
                FrameClassifier.FrameWork prev = works.putIfAbsent(wkey, built);
                work = prev == null ? built : prev;
            }
            double self = -1;
            double best = Double.POSITIVE_INFINITY;
            int ownPos = selfOf[i];
            for (int j = 0; j < cands.size(); j++) {
                Cand cd = cands.get(j);
                double s = classifier.verifyKindScore(work, cd.art, kind,
                        cd.ctx.clickLeft() == null ? 0 : cd.ctx.clickLeft(),
                        cd.ctx.clickTop() == null ? 0 : cd.ctx.clickTop());
                if (s < 0) {
                    continue;
                }
                if (j == ownPos) {
                    self = s;
                }
                if (s < best) {
                    best = s;
                }
            }
            if (self < 0 || best == Double.POSITIVE_INFINITY) {
                return;
            }
            synchronized (selfSum) {
                selfScore[i] = self;
                bestScore[i] = best;
                selfSum[ownPos] += self;
                cnt[ownPos]++;
                if (self == best) {
                    hit[ownPos]++;
                }
            }
            Run rr = run;
            if (rr != null) {
                rr.processed++;
            }
        });

        // 整库汇总 + 分类级行
        int totalSamples = 0;
        double totalA = 0;
        int totalHit = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int j = 0; j < cands.size(); j++) {
            int c = cnt[j];
            if (c == 0) {
                continue;
            }
            totalSamples += c;
            totalA += selfSum[j];
            totalHit += hit[j];
            Cand cd = cands.get(j);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", cd.ctx.state());
            row.put("action", cd.ctx.action());
            row.put("clickLeft", cd.ctx.clickLeft());
            row.put("clickTop", cd.ctx.clickTop());
            row.put("samples", c);
            row.put("a", selfSum[j] / c);
            row.put("b", hit[j] * 100.0 / c);
            row.put("hit", hit[j]);
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("state")).compareTo(String.valueOf(b.get("state"))));

        Double A = totalSamples == 0 ? null : totalA / totalSamples;
        Double B = totalSamples == 0 ? null : totalHit * 100.0 / totalSamples;
        return new KindStat(kind, fp, System.currentTimeMillis(), (int) (System.currentTimeMillis() - t0),
                A, B, totalSamples, cands.size(), rows);
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

}
