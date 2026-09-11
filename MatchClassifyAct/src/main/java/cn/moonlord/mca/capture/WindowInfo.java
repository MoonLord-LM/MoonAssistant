package cn.moonlord.mca.capture;

import com.sun.jna.platform.win32.WinDef.HWND;
import lombok.Builder;
import lombok.Data;

import java.awt.Rectangle;

/**
 * 目标窗口的描述信息。
 */
@Data
@Builder
public class WindowInfo {

    /** Windows 顶层窗口句柄 */
    private HWND hwnd;

    /** 窗口标题 */
    private String title;

    /** 窗口在屏幕上的矩形（物理像素，是采集器裁剪显示器的依据） */
    private Rectangle bounds;

    /** 窗口是否已最小化 */
    private boolean minimized;

}
