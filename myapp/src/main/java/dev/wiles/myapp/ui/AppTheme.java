package dev.wiles.myapp.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;

/**
 * 界面风格集中定义处。想把整个 UI 换个调子，改这里就够了。
 *
 * <p>整体走「柔和卡片 + 蓝色渐变」路线：浅灰底衬托白色圆角卡片，
 * 主题色用于强调与进度，状态色区分完成/危险/提示。
 */
public final class AppTheme {

    private AppTheme() {
    }

    // --- 配色 ---------------------------------------------------------------
    /** 页面背景（浅灰，衬托白色卡片）。 */
    public static final Color BACKGROUND = new Color(0xF4F6FB);
    /** 卡片/输入框背景。 */
    public static final Color SURFACE = Color.WHITE;
    /** 交替/悬浮层背景。 */
    public static final Color SURFACE_ALT = new Color(0xFAFBFE);

    /** 主题色，用于按钮、进度、强调。 */
    public static final Color ACCENT = new Color(0x4F7DF3);
    public static final Color ACCENT_HOVER = new Color(0x3D68DD);
    public static final Color ACCENT_PRESSED = new Color(0x2F55C4);
    /** 渐变顶部/底部，做 banner 用。 */
    public static final Color ACCENT_LIGHT = new Color(0x6E9BFF);
    public static final Color ACCENT_DEEP = new Color(0x3B5BE0);

    /** 辅助强调（薄荷绿），用于完成态。 */
    public static final Color SUCCESS = new Color(0x2BB673);
    public static final Color SUCCESS_SOFT = new Color(0xE4F7EE);
    /** 提示（琥珀），用于进行中的统计。 */
    public static final Color WARNING = new Color(0xF0A83C);
    public static final Color WARNING_SOFT = new Color(0xFDF3E2);
    /** 危险/删除。 */
    public static final Color DANGER = new Color(0xE5484D);
    public static final Color DANGER_SOFT = new Color(0xFDECEE);

    public static final Color TEXT_PRIMARY = new Color(0x1F2430);
    public static final Color TEXT_SECONDARY = new Color(0x6B7280);
    public static final Color TEXT_TERTIARY = new Color(0x9AA2B1);
    /** 已完成任务的置灰颜色。 */
    public static final Color TEXT_DONE = new Color(0xAAB1BD);
    public static final Color TEXT_ON_ACCENT = Color.WHITE;

    /** 卡片描边（很淡）。 */
    public static final Color BORDER = new Color(0xE4E8F0);
    public static final Color BORDER_STRONG = new Color(0xD3DAE6);

    /** 悬停/选中的浅色底。 */
    public static final Color HOVER = new Color(0xF0F4FE);
    public static final Color SELECTION = new Color(0xE6EDFD);
    public static final Color CHECK_DONE = ACCENT;

    // --- 尺寸 ---------------------------------------------------------------
    public static final int CARD_RADIUS = 14;
    public static final int CHIP_RADIUS = 999;
    public static final int ROW_HEIGHT = 64;
    public static final int PADDING = 20;
    public static final int GAP = 12;
    /** 卡片阴影的偏移与层数（手绘柔化）。 */
    public static final int SHADOW_OFFSET = 2;

    // --- 字体 ---------------------------------------------------------------
    /** 优先用系统里好看的中文字体，逐级回退。 */
    public static final String FONT_FAMILY = pickFont(
            "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", "SansSerif");

    public static Font font(int style, int size) {
        return new Font(FONT_FAMILY, style, size);
    }

    public static Font regular(int size) {
        return font(Font.PLAIN, size);
    }

    public static Font bold(int size) {
        return font(Font.BOLD, size);
    }

    /** 把颜色按比例调亮（ratio>0）或调暗（ratio<0）。 */
    public static Color shade(Color c, double ratio) {
        double r = ratio < 0 ? 1 + ratio : 1;
        double add = ratio > 0 ? 255 * ratio : 0;
        int red = clamp((int) (c.getRed() * r + add));
        int green = clamp((int) (c.getGreen() * r + add));
        int blue = clamp((int) (c.getBlue() * r + add));
        return new Color(red, green, blue, c.getAlpha());
    }

    /** 半透明副本。 */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp(a));
    }

    /** 混合两种颜色，t 为 b 的权重（0..1）。 */
    public static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                clamp((int) (a.getRed() + (b.getRed() - a.getRed()) * t)),
                clamp((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clamp((int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)));
    }

    /** 竖直渐变（顶部 top → 底部 bottom）。 */
    public static Paint verticalGradient(Color top, Color bottom, int height) {
        return new GradientPaint(0, 0, top, 0, Math.max(1, height), bottom);
    }

    /** 水平渐变。 */
    public static Paint horizontalGradient(Color left, Color right, int width) {
        return new LinearGradientPaint(0, 0, Math.max(1, width), 0,
                new float[]{0f, 1f}, new Color[]{left, right});
    }

    /** 画一个柔和的圆角投影（多层递减透明度模拟模糊）。 */
    public static void paintSoftShadow(Graphics2D g2, int x, int y, int w, int h, int radius) {
        for (int i = SHADOW_OFFSET + 1; i >= 1; i--) {
            int a = 10 + (SHADOW_OFFSET - i) * 6;
            g2.setColor(new Color(0x2F, 0x3A, 0x55, clamp(a)));
            g2.fillRoundRect(x - i, y - i + 1, w + i * 2, h + i * 2, radius + i, radius + i);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String pickFont(String... candidates) {
        var available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        var set = java.util.Set.of(available);
        for (String name : candidates) {
            if (set.contains(name)) {
                return name;
            }
        }
        return "SansSerif";
    }
}
