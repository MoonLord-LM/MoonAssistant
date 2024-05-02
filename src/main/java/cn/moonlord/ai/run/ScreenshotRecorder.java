package cn.moonlord.ai.run;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

@Data
@Slf4j
@Component
public class ScreenshotRecorder {

    private volatile BufferedImage screenCapture;

    @SneakyThrows
    @Async
    @Scheduled(fixedRate = 2 * 1000)
    public void record() {
        // fix: java.awt.AWTException: headless environment
        System.setProperty("java.awt.headless", "false");

        // realRectangle is the real resolution of display screen, such as 3840 * 2160
        GraphicsDevice[] gs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        DisplayMode mode = gs[gs.length - 1].getDisplayMode();
        Rectangle realRectangle = new Rectangle(mode.getWidth(), mode.getHeight());

        // virtualRectangle is the virtual resolution after Windows system scaling, such as 2560 * 1440（x 150%）
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle virtualRectangle = new Rectangle(screenSize);
        log.debug("screenshot realRectangle: {}, virtualRectangle: {}", realRectangle, virtualRectangle);

        Robot robot = new Robot();
        List<Image> captures = robot.createMultiResolutionScreenCapture(virtualRectangle).getResolutionVariants();
        screenCapture = (BufferedImage) captures.get(captures.size() - 1);
    }

    @SneakyThrows
    public BufferedImage getScaleScreenCapture(Double scale) {
        BufferedImage screenCapture = getScreenCapture();
        if (scale != null && scale > 0 && scale < 1) {
            int scaledWidth = (int) (screenCapture.getWidth() * scale);
            int scaledHeight = (int) (screenCapture.getHeight() * scale);
            BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaledImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.drawImage(screenCapture, 0, 0, scaledWidth, scaledHeight, null);
            g.dispose();
            return scaledImage;
        }
        return screenCapture;
    }

}
