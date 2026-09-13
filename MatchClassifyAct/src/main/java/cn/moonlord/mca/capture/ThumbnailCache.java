package cn.moonlord.mca.capture;

import cn.moonlord.mca.config.StoragePaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 截图缩略图缓存：把每张截图按固定步长 {@link #BLOCK} 抽样成「长宽各 1/{@link #BLOCK}」的小图，
 * 供<b>运行期去重判定</b>（自动截图循环、手动采集 / 存入分类 / 存到待标注）先做一次廉价预筛
 * （见 {@link ScreenCaptureService#scanReference}）：缩略图只有原图 1/{@link #BLOCK}² 的像素量，
 * 读一张缩略图比解一张全尺寸原图快得多。启动时的历史重复清理仍读全尺寸原图逐像素比对。
 *
 * <p>每个方块只取<b>一个</b>像素 —— 该方块<b>中心附近</b>那一点（第 {@link #OFFSET} 行第 {@link #OFFSET} 列，
 * 即步长的一半）。所以缩略图不是缩放图、也不是平均图，而是原图的一张规则抽样：
 * 分散在整幅画面上的小差异会按比例反映到缩略图上，而只在方块内部几个像素上发生的局部差异可能刚好落在
 * 取样点之外、被这一步漏掉 —— 因此它只用于预筛「这一对几乎一样」：命中即判重复、不再解码原图；
 * 预筛没拦下的仍读全尺寸原图逐像素精确比对（「判为新画面」这一侧不放宽）。</p>
 *
 * <p>两级缓存：磁盘 {@code resource/cache/<原图同名>.png}（目录名由 {@code capture.cache-dir} 配，
 * 与原图一一对应、可直接打开对照）+ 内存 {@link #memory}（弱引用，命中连小图都不用读）。
 * 内存条目以「文件名 + 大小 + 最后修改时间」为身份（{@link Key}），三者有一项对不上就不再命中、按需重建；
 * 磁盘那份与内存同源、同目录同名，有效性判据是「不比原图旧」（原图被改过必然 mtime 更新 → 该缩略图作废）。
 * 磁盘这份是可删缓存 —— 整目录删掉只会让下一次判定多解几张原图，判定结果不受影响；
 * 原图被删除 / 移出基准目录后，其缩略图（含内存条目）会在下次基准枚举时一并裁掉（见 {@link #prune}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailCache {

    /** 抽样步长：缩略图长宽各为原图的 1/{@value #BLOCK}（每个步长方块只取一个像素） */
    private static final int BLOCK = 10;

    /** 方块内的取样位置：取近似中心的那一点（步长的一半，即第 {@value} 行 / 列） */
    private static final int OFFSET = BLOCK / 2;

    private final StoragePaths storage;

    /** 一张原图在内存缓存里的身份：文件名 + 大小 + 最后修改时间<b>三者都对上</b>才算同一张图。
     *  任一项变了（改了内容、换了文件、重名替换）就是另一个 Key，旧条目自然不再命中，缓存及时失效。 */
    private record Key(String name, long size, long mtime) {
    }

    /** 内存缩略图缓存（Key → 弱引用）：命中就完全不碰磁盘。值为 {@link WeakReference}，内存紧张时
     *  GC 可直接回收这些像素数组（下一次判定按需重读小图、读不出来再重建），所以它不会把堆撑大。
     *  已删除原图的条目、以及被 GC 回收的条目在 {@link #prune} 里顺带清掉。 */
    private final Map<Key, WeakReference<int[]>> memory = new ConcurrentHashMap<>();

    /** 原图对应的缩略图文件（同目录同名，{@code resource/cache/<原图文件名>}） */
    private static Path thumbOf(Path source, Path dir) {
        return dir.resolve(source.getFileName().toString());
    }

    /** 原图的身份（文件名 + 大小 + 最后修改时间）；文件不在 / stat 失败返回 null（按「没有缩略图」处理） */
    private static Key keyOf(Path source) {
        try {
            BasicFileAttributes attr = Files.readAttributes(source, BasicFileAttributes.class);
            return new Key(source.getFileName().toString(), attr.size(), attr.lastModifiedTime().toMillis());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 按 {@link #BLOCK} 步长抽样出缩略图像素（低 24 位 RGB，行优先、忽略 alpha，与全尺寸比对同口径）。
     *
     * @return 缩略图像素数组；图为空 / 长或宽不足一个 {@link #BLOCK}（缩不出一个像素）返回 null，
     *         调用方按「没有缩略图 = 不可预筛」处理、退回全尺寸比对
     */
    public static int[] sample(BufferedImage image) {
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int tw = w / BLOCK;
        int th = h / BLOCK;
        if (tw <= 0 || th <= 0) {
            return null;
        }
        int[] out = new int[tw * th];
        int i = 0;
        for (int ty = 0; ty < th; ty++) {
            int y = ty * BLOCK + OFFSET;
            for (int tx = 0; tx < tw; tx++) {
                out[i++] = image.getRGB(tx * BLOCK + OFFSET, y) & 0xffffff;
            }
        }
        return out;
    }

    /** 两张缩略图的「不一致像素点占比」（%，与全尺寸比对同口径的两位舍入）。
     *  任一侧没有缩略图、或可比的像素个数不同（尺寸不同）返回 100 = 不可比（调用方退回全尺寸比对）。 */
    public static double mismatchPercent(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 100;
        }
        int bad = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                bad++;
            }
        }
        return Math.round(bad * 10000.0 / a.length) / 100.0;
    }

    /**
     * 取一张原图的缩略图像素：内存命中直接返回（不碰磁盘）；否则读磁盘缩略图；都没有才解一次原图、
     * 抽样后把缩略图写进磁盘与内存（写失败只告警，本次仍按刚抽出的像素参与预筛）。
     *
     * @return 缩略图像素；原图读不出来 / 缩不出缩略图返回 null（调用方按不可预筛处理）
     */
    public int[] of(Path source) {
        if (source == null) {
            return null;
        }
        Key key = keyOf(source);
        if (key == null) {
            return null;   // 原图不在 / stat 失败：当作没有可用缩略图
        }
        WeakReference<int[]> hit = memory.get(key);
        if (hit != null) {
            int[] pixels = hit.get();
            if (pixels != null) {
                return pixels;   // 内存命中（未被 GC 回收）：连小图都不用读
            }
        }
        Path thumb = thumbOf(source, storage.cache());
        if (isFresh(thumb, key.mtime())) {
            int[] cached = readPixels(thumb);
            if (cached != null) {
                memory.put(key, new WeakReference<>(cached));
                return cached;
            }
        }
        BufferedImage full = readImage(source);
        if (full == null) {
            return null;
        }
        int[] pixels = sample(full);
        if (pixels != null) {
            writePixels(thumb, full.getWidth() / BLOCK, full.getHeight() / BLOCK, pixels);
            memory.put(key, new WeakReference<>(pixels));
        }
        return pixels;
    }

    /** 把一张刚保存的原图的缩略图写进磁盘与内存缓存（原图落盘后调用）：省掉后续首次比对为它解一次全尺寸原图。
     *  best-effort：失败只告警，下次比对会按需重建。 */
    public void write(Path source, BufferedImage image) {
        if (source == null || image == null) {
            return;
        }
        int[] pixels = sample(image);
        if (pixels == null) {
            return;
        }
        writePixels(thumbOf(source, storage.cache()), image.getWidth() / BLOCK, image.getHeight() / BLOCK, pixels);
        Key key = keyOf(source);
        if (key != null) {
            memory.put(key, new WeakReference<>(pixels));   // 顺手进内存：紧接着的那次判定立刻就能命中
        }
    }

    /**
     * 裁剪两侧缓存（基准目录枚举完原图全集后调用）：原图已被删除 / 移出基准目录的条目一并丢掉
     * （磁盘缩略图与内存条目），缓存不随历史无限增长。缩略图只是加速用，删了下次按需重建。
     * 磁盘只清理自己的两类文件（与原图同名的 {@code .png} 与写一半留下的 {@code .png.tmp}），
     * 目录里其它文件一律不碰。
     *
     * @param sourceNames 当前基准目录里的原图文件名集合
     * @return 删掉的磁盘缩略图张数（供日志）
     */
    public int prune(Set<String> sourceNames) {
        if (sourceNames.isEmpty()) {
            return 0;   // 一张原图都没有：不动缓存（枚举异常时不要误清）
        }
        // 内存侧：不在基准里的丢掉；已被 GC 回收（弱引用为空）的条目也顺带清掉
        memory.entrySet().removeIf(e -> e.getValue().get() == null || !sourceNames.contains(e.getKey().name()));
        Path dir = storage.cache();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        // 只碰自己的两种文件：缩略图（与原图同名 .png）与写一半留下的临时文件；其余文件一律不动
                        return (name.endsWith(".png") && !sourceNames.contains(p.getFileName().toString()))
                                || name.endsWith(".png.tmp");
                    })
                    .forEach(stale::add);
        } catch (IOException e) {
            log.warn("枚举缩略图目录 {} 失败，本次不裁剪: {}", dir, e.toString());
            return 0;
        }
        int dropped = 0;
        for (Path p : stale) {
            try {
                Files.deleteIfExists(p);
                dropped++;
            } catch (IOException e) {
                log.debug("删除缩略图 {} 失败: {}", p.getFileName(), e.toString());
            }
        }
        return dropped;
    }

    /** 磁盘缩略图是否还有效：存在且不比原图旧（原图内容被改过 → 原图 mtime 更新 → 该缩略图作废、按需重建） */
    private static boolean isFresh(Path thumb, long sourceMtime) {
        try {
            return Files.isRegularFile(thumb)
                    && Files.getLastModifiedTime(thumb).toMillis() >= sourceMtime;
        } catch (IOException | RuntimeException e) {
            return false;   // 原图刚被删 / stat 失败：按「没有可用缩略图」处理
        }
    }

    /** 读一张缩略图 PNG 的像素（低 24 位 RGB）；读不出来返回 null */
    private static int[] readPixels(Path thumb) {
        BufferedImage img = readImage(thumb);
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        try {
            int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] &= 0xffffff;
            }
            return pixels;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读一张 PNG；读不出来 / 解码为空返回 null */
    private static BufferedImage readImage(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 原子落盘一张缩略图（.tmp → 原子改名）：中途被杀不会留下半截图片 */
    private void writePixels(Path file, int w, int h, int[] pixels) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, pixels, 0, w);
            if (!ImageIO.write(img, "png", tmp.toFile())) {
                Files.deleteIfExists(tmp);
                log.warn("缩略图编码为 PNG 失败，{} 本次不写缓存", file.getFileName());
                return;
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("写缩略图 {} 失败（本次判定不受影响，只是下次还要再缩一次）: {}", file.getFileName(), e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }
}
