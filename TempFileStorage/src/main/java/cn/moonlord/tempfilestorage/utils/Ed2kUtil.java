package cn.moonlord.tempfilestorage.utils;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 生成 ED2K 链接
 */
@Slf4j
public class Ed2kUtil {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final int HASH_BLOCK_SIZE = 9728000;

    @SneakyThrows
    public static String generateLink(byte[] fileBytes, String fileName) {
        try (InputStream input = new ByteArrayInputStream(fileBytes)) {
            byte[] ed2kHash = computeEd2kHash(input);
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
            return String.format("ed2k://|file|%s|%d|%s|", encodedFileName, fileBytes.length, Hex.toHexString(ed2kHash));
        }
    }

    @SneakyThrows
    public static String generateLink(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] ed2kHash = computeEd2kHash(input);
            String encodedFileName = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8);
            return String.format("ed2k://|file|%s|%d|%s|", encodedFileName, file.length(), Hex.toHexString(ed2kHash));
        }
    }

    @SneakyThrows
    public static byte[] computeEd2kHash(InputStream input) {
        MessageDigest md4 = MessageDigest.getInstance("MD4", "BC");
        List<byte[]> partHashes = new ArrayList<>();
        byte[] buffer = new byte[HASH_BLOCK_SIZE];

        int read;
        while ((read = input.read(buffer)) != -1) {
            byte[] actualBytes = (read == HASH_BLOCK_SIZE) ? buffer : Arrays.copyOf(buffer, read);
            partHashes.add(md4.digest(actualBytes));
            md4.reset();
        }

        byte[] ed2kHash;
        if (partHashes.size() == 1) {
            ed2kHash = partHashes.get(0);
        } else {
            int totalLen = partHashes.size() * 16;
            byte[] all = new byte[totalLen];
            for (int i = 0; i < partHashes.size(); i++) {
                System.arraycopy(partHashes.get(i), 0, all, i * 16, 16);
            }
            ed2kHash = md4.digest(all);
        }
        return ed2kHash;
    }

}
