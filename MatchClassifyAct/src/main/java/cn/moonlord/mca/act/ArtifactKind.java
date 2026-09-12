package cn.moonlord.mca.act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产物维度（kind）注册表：识别端 {@link FrameClassifier} 与产物端
 * {@code mark.ThinkService} 共用的唯一权威定义。
 *
 * <p>此前 kind 字面量在识别端（EXACT_KINDS / KIND_DOWN / CLICK_CROP_KINDS / ATTN_CROP_KINDS /
 * KIND_FILE / KIND_ORDER / MATCH_*_FILES）与产物端（FILE_* / SAME_TIERS /
 * BASE_KINDS / UNIQUE_BASE_KINDS / ATTN_ALL_KINDS / CLICK_ALL_KINDS / KIND_FILE /
 * 各 *ArtifactsComplete）各写一遍，加一个 kind 要改十多处、极易漏改。现在全部由本表派生：
 * 新增 / 改名 / 删除一个产物 kind 只改本文件。</p>
 *
 * <p>每个分类的产物 = 15 张基础合成图 + 15 张对应 -unique 独有区图 + 12 张注意区交集图
 * （共 42 张，每个分类都有）；鼠标点击分类另有 12 张点击区交集图（共 54 张）。</p>
 */
public final class ArtifactKind {

    /** 块内压缩模式：块内均值 / 块内多数 / 块内去重均值 */
    public static final int MODE_MEAN = 0;
    public static final int MODE_MAJOR = 1;
    public static final int MODE_DEDUP_MEAN = 2;

    /** 产物形态：非方框图（全幅/块图）、注意区方框图（框心 = 注意点）、点击区方框图（框心 = 鼠标点击点） */
    public enum Crop { NONE, ATTN, CLICK }

    /** 产物族（只用于展示分组与命名）：交集 / 多数 / 均值 / 去重均值 / 方框交集区 */
    public enum Family { INTERSECT, MAJOR, AVG, DEDUP_AVG, CROP }

    /** 一个产物维度 */
    public record Def(String kind, String file, int block, int mode, Crop crop, Family family, String uniqueOf) {
        /** 是否走「逐像素完全一致」判据（交集/多数/方框图）；false = 均值类走逐通道容差 */
        public boolean exact() {
            return family != Family.AVG && family != Family.DEDUP_AVG;
        }

        /** 交集档位（same90/same80/…）：交集族取自身（去 -unique 后缀）、方框交集族取「-」之后的档位；其余返回 null */
        public String tier() {
            if (family == Family.CROP) {
                return kind.substring(kind.indexOf('-') + 1);
            }
            int i = kind.indexOf("-");
            return family == Family.INTERSECT ? kind.substring(0, i < 0 ? kind.length() : i) : null;
        }

        /** 方框交集图的分割除数（框 = 整幅长宽 ÷ 该值）；非方框图返回 1 */
        public int cropDiv() {
            if (crop == Crop.NONE) {
                return 1;
            }
            int i = kind.indexOf('-');
            return Integer.parseInt(kind.substring(crop == Crop.CLICK ? "click".length() : "attn".length(), i));
        }
    }

    /** info.json 文件名（非产物 kind，但同样只在产物目录里出现一次） */
    public static final String FILE_INFO = "info.json";

    /** 交集主档 kind 名：覆盖率、展示主图、各 *ArtifactsComplete 的「两图齐否」都以它为准 */
    public static final String PRIMARY_TIER = "same90";

    /** 交集六档（固定展示顺序：100% 档在前） */
    private static final List<String> TIERS =
            List.of("same100", "same90", "same80", "same70", "same60", "same50");

    /** 交集档位（产物生成顺序：主档 same90 起、100% 档殿后） */
    private static final List<String> TIER_GEN_ORDER =
            List.of("same90", "same80", "same70", "same60", "same50", "same100");

    /** 各交集档位的一致门槛：同档判定「一致张数 > 样本数 × 阈值」（严格大于），100% 档特判为完全一致 */
    private static final Map<String, Double> TIER_AGREE = Map.of(
            "same90", 0.90, "same80", 0.80, "same70", 0.70,
            "same60", 0.60, "same50", 0.50, "same100", 1.0);

    /** 方框交集图的除数（框 = 整幅长宽 ÷ 该值）：1/8、1/32 */
    private static final int[] CROP_DIVS = {8, 32};

    /** 汇总分析主产物对应的 10 张基础图 kind（交集 90% 主档 + 多数/均值/去重均值/8·32 块族） */
    private static final List<String> PRIMARY_BASE_KINDS = List.of(
            "same90", "max", "avg", "dedup-avg", "major8", "avg8", "dedup-avg8", "major32", "avg32", "dedup-avg32");

    /** 另 5 张交集档基础图 kind（100/80/70/60/50 档），与主产物 10 张合成 15 张基础图 */
    private static final List<String> EXTRA_BASE_KINDS = List.of("same80", "same70", "same60", "same50", "same100");

    /** 需要生成 -unique 独有区图的 15 张基础图 kind（顺序 = 产物端逐 kind 计算/进度顺序：主产物在前） */
    private static final List<String> UNIQUE_BASE_ORDER = concat(PRIMARY_BASE_KINDS, EXTRA_BASE_KINDS);

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return List.copyOf(l);
    }

    /** 固定展示/比较顺序：交集六档各带 -unique → 多数族 → 均值族 → 去重均值族 → 点击区 → 注意区 */
    private static final List<Def> ALL = build();

    private static final List<String> KINDS = ALL.stream().map(Def::kind).toList();
    private static final Map<String, Def> BY_KIND = index(ALL);

    private ArtifactKind() {
    }

    private static List<Def> build() {
        List<Def> l = new ArrayList<>();
        // 交集六档（100% 档在前）：各带 -unique 独有区图、全幅不压缩 → 族 A
        for (String tier : TIERS) {
            l.add(new Def(tier, fileName(tier), 1, MODE_MEAN, Crop.NONE, Family.INTERSECT, null));
            l.add(new Def(tier + "-unique", fileName(tier + "-unique"), 1, MODE_MEAN, Crop.NONE, Family.INTERSECT, tier));
        }
        // 多数族 B：全幅 max（磁盘名 major.png；块=1 时压缩模式无意义，与原表一致取均值口径）、1/8、1/32
        addWithUnique(l, "max", 1, MODE_MEAN, Family.MAJOR);
        addWithUnique(l, "major8", 8, MODE_MAJOR, Family.MAJOR);
        addWithUnique(l, "major32", 32, MODE_MAJOR, Family.MAJOR);
        // 均值族 C
        addWithUnique(l, "avg", 1, MODE_MEAN, Family.AVG);
        addWithUnique(l, "avg8", 8, MODE_MEAN, Family.AVG);
        addWithUnique(l, "avg32", 32, MODE_MEAN, Family.AVG);
        // 去重均值族 D
        addWithUnique(l, "dedup-avg", 1, MODE_DEDUP_MEAN, Family.DEDUP_AVG);
        addWithUnique(l, "dedup-avg8", 8, MODE_DEDUP_MEAN, Family.DEDUP_AVG);
        addWithUnique(l, "dedup-avg32", 32, MODE_DEDUP_MEAN, Family.DEDUP_AVG);
        // 方框交集区 E：点击区（仅鼠标点击分类）→ 注意区（每个分类），各 1/8、1/32 六档
        for (int div : CROP_DIVS) {
            for (String tier : TIERS) {
                addCrop(l, "click", div, tier, Crop.CLICK);
            }
        }
        for (int div : CROP_DIVS) {
            for (String tier : TIERS) {
                addCrop(l, "attn", div, tier, Crop.ATTN);
            }
        }
        return List.copyOf(l);
    }

    private static void addWithUnique(List<Def> l, String kind, int block, int mode, Family family) {
        l.add(new Def(kind, fileName(kind), block, mode, Crop.NONE, family, null));
        l.add(new Def(kind + "-unique", fileName(kind + "-unique"), block, mode, Crop.NONE, family, kind));
    }

    private static void addCrop(List<Def> l, String prefix, int div, String tier, Crop crop) {
        String kind = prefix + div + "-" + tier;
        l.add(new Def(kind, fileName(kind), 1, MODE_MEAN, crop, Family.CROP, null));
    }

    /** 多数族的全幅图磁盘名是 major.png（kind 名仍是 max），其余 kind 一律 kind + ".png" */
    private static String fileName(String kind) {
        return switch (kind) {
            case "max" -> "major.png";
            case "max-unique" -> "major-unique.png";
            default -> kind + ".png";
        };
    }

    private static Map<String, Def> index(List<Def> all) {
        Map<String, Def> m = new LinkedHashMap<>();
        for (Def d : all) {
            m.put(d.kind(), d);
        }
        return Map.copyOf(m);
    }

    /** 全部产物维度（固定展示/比较顺序） */
    public static List<Def> all() {
        return ALL;
    }

    /** 全部产物 kind（固定展示/比较顺序） */
    public static List<String> kinds() {
        return KINDS;
    }

    /** 查一个 kind 的定义；未知 kind 返回 null */
    public static Def of(String kind) {
        return kind == null ? null : BY_KIND.get(kind);
    }

    public static boolean has(String kind) {
        return of(kind) != null;
    }

    /** kind → 产物文件名；未知 kind 返回 null */
    public static String file(String kind) {
        Def d = of(kind);
        return d == null ? null : d.file();
    }

    /** 指定形态的全部 kind */
    public static List<String> cropKinds(Crop crop) {
        return ALL.stream().filter(d -> d.crop() == crop).map(Def::kind).toList();
    }

    /** 15 张 -unique 独有区图 kind */
    public static List<String> uniqueKinds() {
        return ALL.stream().filter(d -> d.uniqueOf() != null).map(Def::kind).toList();
    }

    /** 需要生成 -unique 的 15 张基础图 kind（顺序 = 产物端逐 kind 计算/进度顺序） */
    public static List<String> uniqueBaseOrder() {
        return UNIQUE_BASE_ORDER;
    }

    /** 汇总分析主产物对应的 10 张基础图 kind */
    public static List<String> primaryBaseKinds() {
        return PRIMARY_BASE_KINDS;
    }

    /** 另 5 张交集档基础图 kind */
    public static List<String> extraBaseKinds() {
        return EXTRA_BASE_KINDS;
    }

    /** 注意区交集图全部 kind（12 张，每个分类都有） */
    public static List<String> attnKinds() {
        return cropKinds(Crop.ATTN);
    }

    /** 点击区交集图全部 kind（12 张，仅鼠标点击分类有） */
    public static List<String> clickKinds() {
        return cropKinds(Crop.CLICK);
    }

    /** 交集档位生成顺序（主档 same90 起、100% 档殿后） */
    public static List<String> tierGenOrder() {
        return TIER_GEN_ORDER;
    }

    /** 交集档位一致门槛；非交集档返回 -1 */
    public static double tierAgree(String tier) {
        Double v = TIER_AGREE.get(tier);
        return v == null ? -1 : v;
    }

    /** 方框交集图的除数（1/8、1/32） */
    public static int[] cropDivs() {
        return CROP_DIVS.clone();
    }

    /** 「全部」组「与代表图差异最大的那张原图」的 kind 后缀 */
    public static final String ALL_MAXDIFF = "-maxdiff";

    /** 「全部」组的 12 张产物 kind（顺序 = 界面卡片顺序）：交集六档六张（100% 档公共部分、90/80/70/60/50 档
     *  稳定区，与分类产物同口径同算法），多数 / 均值 / 去重均值三族各「代表图 + 与代表图差异最大的那张原图」 */
    private static final List<String> ALL_KINDS = concat(TIERS, List.of(
            "max", "max" + ALL_MAXDIFF,
            "avg", "avg" + ALL_MAXDIFF,
            "dedup-avg", "dedup-avg" + ALL_MAXDIFF));

    /** 「全部」组的 12 张产物 kind（不属于 kinds()：不参与识别 / 验证 / 调优 / 完备性门禁） */
    public static List<String> allKinds() {
        return ALL_KINDS;
    }

    /** 是否「全部」组专用产物 kind */
    public static boolean allKind(String kind) {
        return kind != null && ALL_KINDS.contains(kind);
    }

    /** 是否「全部」组的「最大差异图」（kind 以 {@link #ALL_MAXDIFF} 结尾） */
    public static boolean allMaxDiff(String kind) {
        return allKind(kind) && kind.endsWith(ALL_MAXDIFF);
    }

    /** 「全部」组 kind 对应的族代表图 kind（差异最大图返回其代表图；代表图返回自身；非本组返回 null） */
    public static String allBase(String kind) {
        if (!allKind(kind)) {
            return null;
        }
        int i = kind.lastIndexOf(ALL_MAXDIFF);
        return i < 0 ? kind : kind.substring(0, i);
    }

    /** 「全部」组产物文件名：代表图沿用标准产物名（多数族全幅 = major.png），差异最大图 = 代表图名 + -maxdiff */
    public static String allFile(String kind) {
        String base = allBase(kind);
        if (base == null) {
            return null;
        }
        String rep = fileName(base);
        return allMaxDiff(kind)
                ? rep.substring(0, rep.length() - ".png".length()) + ALL_MAXDIFF + ".png"
                : rep;
    }

    /** 「全部」组产物卡片标题：代表图沿用标准标题，差异最大图 = 〈族名〉最大差异图（与〈族名〉差异最大的一张原图） */
    public static String allLabel(String kind) {
        String base = allBase(kind);
        if (base == null) {
            return null;
        }
        if (!allMaxDiff(kind)) {
            return label(base);
        }
        String fam = allFamilyName(base);
        return fam + "最大差异图（与" + fam + "差异最大的一张原图）";
    }

    /** 「全部」组代表图的族中文名：交集图 / 多数图 / 均值图 / 去重均值图 */
    public static String allFamilyName(String repKind) {
        Def d = of(repKind);
        if (d == null) {
            return null;
        }
        return d.family() == Family.INTERSECT ? "交集图" : familyName(d.family()) + "图";
    }

    /** kind → 对照图卡片标题（前端 .tn 文案）：主名与特征验证列表（前端 vkInfo）一致，
     *  括号内是该图「每个像素怎么来的」生成口径（-unique 独有区图以基础图名引用、只说明剔除口径）；
     *  未知 kind 返回 null */
    public static String label(String kind) {
        Def d = of(kind);
        if (d == null) {
            return null;
        }
        if (d.uniqueOf() != null) {
            String base = baseName(d);
            return base + " · 独有区（将 " + base + "，剔除掉其它分类的 " + base + " 出现过的颜色）";
        }
        return switch (d.family()) {
            case INTERSECT -> baseName(d) + "（每个不透明的像素点，" + agreeDesc(d) + "）";
            case MAJOR, AVG, DEDUP_AVG -> blockTitle(d);
            case CROP -> baseName(d) + "（以" + (d.crop() == Crop.CLICK ? "点击点" : "关注点")
                    + "为中心的方框内，每个不透明的像素点，" + agreeDesc(d) + "）";
        };
    }

    /** 对照图主名（不含 -unique 后缀与括号口径）：交集图 90% / 多数图 1/8 / 点击区交集 1/8 · 90% */
    private static String baseName(Def d) {
        return switch (d.family()) {
            case INTERSECT -> "交集图 " + percent(d) + "%";
            case MAJOR, AVG, DEDUP_AVG -> familyName(d.family()) + "图 1/" + d.block();
            case CROP -> (d.crop() == Crop.CLICK ? "点击区交集 " : "注意区交集 ") + "1/" + d.cropDiv()
                    + " · " + percent(d) + "%";
        };
    }

    /** 交集（含方框交集）档位的一致口径：100% 档 = 全部样本同色，其余档 = 覆盖不少于该档阈值 */
    private static String agreeDesc(Def d) {
        return "same100".equals(d.tier())
                ? "和全部样本颜色一致"
                : "和不少于" + percent(d) + "%的样本颜色一致";
    }

    /** 交集档位的百分比数字（same90 → 90） */
    private static String percent(Def d) {
        return d.tier().substring("same".length());
    }

    /** 块族中文名：多数 / 均值 / 去重均值 */
    private static String familyName(Family f) {
        return switch (f) {
            case MAJOR -> "多数";
            case AVG -> "均值";
            default -> "去重均值";
        };
    }

    /** 多数 / 均值 / 去重均值族标题：主名 = 族名 + 「1/块边长」（1/1 = 全幅），括号内是逐点（或逐块）口径 */
    private static String blockTitle(Def d) {
        int b = d.block();
        String scope = b == 1 ? "每个像素取" : "每个 " + b + "×" + b + " 块取块内";
        String src = switch (d.family()) {
            case MAJOR -> b == 1 ? "全部样本里该位置出现最多次的颜色" : "全部样本里出现最多次的颜色";
            case AVG -> "全部样本 RGB 的均值";
            default -> "全部样本去重后 RGB 的均值";
        };
        return baseName(d) + "（" + scope + src + "）";
    }
}
