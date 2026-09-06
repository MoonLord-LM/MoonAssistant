package cn.moonlord.mca.utils;

import java.awt.image.BufferedImage;

// 图像比对工具类
public final class ImageUtils {

    // 两图一致的像素数
    public static int sameColorCount(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("图片分辨率不一致，无法比对: a=" + a.getWidth() + "x" + a.getHeight() + ", b=" + b.getWidth() + "x" + b.getHeight());
        }
        return sameColorCount(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w), maxDiff);
    }

    // 两图相近的像素数
    public static int similarColorCount(BufferedImage a, BufferedImage b, int maxDiff) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("图片分辨率不一致，无法比对: a=" + a.getWidth() + "x" + a.getHeight() + ", b=" + b.getWidth() + "x" + b.getHeight());
        }
        return similarColorCount(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w), maxDiff);
    }

    // 两段等长像素序列一致的像素数
    private static int sameColorCount(int[] pa, int[] pb) {
        if (pa.length == 0 || pb.length == 0) {
            throw new IllegalArgumentException("像素序列长度异常，无法比对: a=" + pa.length + ", b=" + pb.length);
        }
        if (pa.length != pb.length) {
            throw new IllegalArgumentException("像素序列长度不一致，无法比对: a=" + pa.length + ", b=" + pb.length);
        }
        int count = 0;
        for (int i = 0; i < pa.length; i++) {
            int cb = pb[i];
            if (((cb >>> 24) & 0xff) < 0x80) {
                continue;
            }
            if (pa[i] == cb) {
                count++;
            }
        }
        return count;
    }

    // 两段等长像素序列相近的像素数（同一坐标逐点对应）
    public static int similarColorCount(int[] pa, int[] pb, int maxDiff) {
        if (pa.length == 0 || pb.length == 0) {
            throw new IllegalArgumentException("像素序列长度异常，无法比对: a=" + pa.length + ", b=" + pb.length);
        }
        if (pa.length != pb.length) {
            throw new IllegalArgumentException("像素序列长度不一致，无法比对: a=" + pa.length + ", b=" + pb.length);
        }
        int count = 0;
        for (int i = 0; i < pa.length; i++) {
            int ca = pa[i];
            int cb = pb[i];
            if (((cb >>> 24) & 0xff) < 0x80) {
                continue;
            }
            if (Math.abs(((ca >> 16) & 0xff) - ((cb >> 16) & 0xff)) <= maxDiff
                    && Math.abs(((ca >> 8) & 0xff) - ((cb >> 8) & 0xff)) <= maxDiff
                    && Math.abs((ca & 0xff) - (cb & 0xff)) <= maxDiff) {
                count++;
            }
        }
        return count;
    }

}
