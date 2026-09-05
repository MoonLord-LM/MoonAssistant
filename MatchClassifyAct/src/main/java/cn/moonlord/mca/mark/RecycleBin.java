package cn.moonlord.mca.mark;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 把文件移入 Windows 系统回收站（软删除，不物理抹除）。
 *
 * <p>实现：调用 PowerShell 的 {@code Microsoft.VisualBasic.FileIO.FileSystem#DeleteFile(...,'SendToRecycleBin')}。
 * 完整脚本以 UTF-16LE 编码后用 {@code -EncodedCommand} 传给 powershell.exe，路径本身先做 UTF-8→Base64，
 * 脚本内解码后再删除——任意字符（含引号/中文/空格）都不需要转义。
 * 对已存在的文件逐个入回收站；调用失败抛 {@link IOException}。</p>
 */
@Slf4j
final class RecycleBin {

    private RecycleBin() {
    }

    /** 把列表中存在的文件移入回收站；返回实际入站的文件。文件已被并发删除的自动跳过。 */
    static List<Path> recycle(List<Path> files) throws IOException {
        List<Path> existed = new ArrayList<>();
        for (Path f : files) {
            if (f != null && Files.isRegularFile(f)) {
                existed.add(f);
            }
        }
        if (existed.isEmpty()) {
            return existed;
        }
        StringBuilder ps = new StringBuilder(512);
        ps.append("Add-Type -AssemblyName Microsoft.VisualBasic; ")
          .append("function D($b){")
          .append("$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b));")
          .append("[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p,'OnlyErrorDialogs','SendToRecycleBin')} ");
        for (Path f : existed) {
            String abs = f.toAbsolutePath().normalize().toString();
            String b64 = Base64.getEncoder().encodeToString(abs.getBytes(StandardCharsets.UTF_8));
            ps.append("D '").append(b64).append("'; ");   // base64 字符集无单引号，可安全置于 '…' 内
        }
        String encoded = Base64.getEncoder()
            .encodeToString(ps.toString().getBytes(StandardCharsets.UTF_16LE));
        Process p;
        try {
            p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            throw new IOException("无法启动 powershell 执行回收站移动", e);
        }
        String out;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            out = "";
        }
        boolean ok;
        try {
            ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        if (!ok) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            String tail = out == null ? "" : out.trim();
            if (tail.length() > 300) {
                tail = tail.substring(tail.length() - 300);
            }
            throw new IOException("移入回收站失败：" + (tail.isEmpty() ? "powershell 执行异常" : tail));
        }
        if (log.isDebugEnabled()) {
            log.debug("已移入回收站：{}", existed);
        }
        return existed;
    }
}
