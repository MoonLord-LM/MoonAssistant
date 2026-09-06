package cn.moonlord.mca.utils;

import java.awt.image.BufferedImage;

// 图像比对工具类
public final class ImageUtils {

    // 两图一致的像素数
    public static int sameColorCount(BufferedImage img1, BufferedImage img2) {
        return similarColorCount(img1, img2, 0);
    }

    // 两段等长像素序列一致的像素数
    public static int sameColorCount(int[] img1, int[] img2) {
        return similarColorCount(img1, img2, 0);
    }

    // 两图 ARGB 4 个值的差距都小于等于 maxDiff 的像素数
    public static int similarColorCount(BufferedImage img1, BufferedImage img2, int maxDiff) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            throw new IllegalArgumentException("图片分辨率不一致，无法比对: img1=" + img1.getWidth() + "x" + img1.getHeight() + ", img2=" + img2.getWidth() + "x" + img2.getHeight());
        }
        return similarColorCount(img1.getRGB(0, 0, img1.getWidth(), img1.getHeight(), null, 0, img1.getWidth()), img2.getRGB(0, 0, img2.getWidth(), img2.getHeight(), null, 0, img2.getWidth()), maxDiff);
    }

    // 两段等长像素序列 ARGB 4 个值的差距都小于等于 maxDiff 的像素数
    public static int similarColorCount(int[] img1, int[] img2, int maxDiff) {
        if (img1.length != img2.length) {
            throw new IllegalArgumentException("像素序列长度不一致，无法比对: img1=" + img1.length + ", img2=" + img2.length);
        }
        if (img1.length == 0) {
            throw new IllegalArgumentException("像素序列长度异常，无法比对: img1=" + img1.length + ", img2=" + img2.length);
        }
        int count = 0;
        for (int i = 0; i < img1.length; i++) {
            int c1 = img1[i];
            int c2 = img2[i];
            if (Math.abs(((c1 >>> 24) & 0xff) - ((c2 >>> 24) & 0xff)) <= maxDiff
             && Math.abs(((c1 >> 16) & 0xff) - ((c2 >> 16) & 0xff)) <= maxDiff
             && Math.abs(((c1 >> 8) & 0xff) - ((c2 >> 8) & 0xff)) <= maxDiff
             && Math.abs((c1 & 0xff) - (c2 & 0xff)) <= maxDiff) {
                count++;
            }
        }
        return count;
    }

    // 比较两图不透明区的像素
    public static CompareResult compareNotTransparent(BufferedImage img1, BufferedImage img2, int maxDiff) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            throw new IllegalArgumentException("图片分辨率不一致，无法比对: img1=" + img1.getWidth() + "x" + img1.getHeight() + ", img2=" + img2.getWidth() + "x" + img2.getHeight());
        }
        return compareNotTransparent(img1.getRGB(0, 0, img1.getWidth(), img1.getHeight(), null, 0, img1.getWidth()), img2.getRGB(0, 0, img2.getWidth(), img2.getHeight(), null, 0, img2.getWidth()), maxDiff);
    }

    // 比较两图不透明区的像素
    public static CompareResult compareNotTransparent(int[] img1, int[] img2, int maxDiff) {
        if (img1.length != img2.length) {
            throw new IllegalArgumentException("像素序列长度不一致，无法比对: img1=" + img1.length + ", img2=" + img2.length);
        }
        if (img1.length == 0) {
            throw new IllegalArgumentException("像素序列长度异常，无法比对: img1=" + img1.length + ", img2=" + img2.length);
        }
        int notTransparent = 0;
        int same = 0;
        int similar = 0;
        for (int i = 0; i < img1.length; i++) {
            int c1 = img1[i];
            int c2 = img2[i];
            if (((c1 >>> 24) & 0xff) == 0 || ((c2 >>> 24) & 0xff) == 0) {
                continue;
            }
            notTransparent++;
            if ((c1 & 0xffffff) == (c2 & 0xffffff)) {
                same++;
                similar++;
            } else {
                if (Math.abs(((c1 >> 16) & 0xff) - ((c2 >> 16) & 0xff)) <= maxDiff
                 && Math.abs(((c1 >> 8) & 0xff) - ((c2 >> 8) & 0xff)) <= maxDiff
                 && Math.abs((c1 & 0xff) - (c2 & 0xff)) <= maxDiff) {
                    similar++;
                }
            }
        }
        return new CompareResult(notTransparent, same, similar);
    }

    // 比对结果
    public static final class CompareResult {
        // 两图均不透明的像素数
        public final int notTransparentCount;
        // 两图不透明区的 RGB 完全一致的像素数
        public final int notTransparentSameCount;
        // 两图不透明区的 RGB 值的差距都小于等于 maxDiff 的像素数
        public final int notTransparentSimilarCount;

        private CompareResult(int notTransparentCount, int notTransparentSameCount, int notTransparentSimilarCount) {
            this.notTransparentCount = notTransparentCount;
            this.notTransparentSameCount = notTransparentSameCount;
            this.notTransparentSimilarCount = notTransparentSimilarCount;
        }
    }

}
