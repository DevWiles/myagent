package dev.wiles.myapp.ui;

import dev.wiles.myapp.Task;

import javax.swing.BorderFactory;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;

/**
 * 把一条 Task 画成一张圆角卡片：左侧圆形复选框、中间标题与创建时间两行、
 * 右侧悬停出现的删除按钮。卡片带柔和投影，悬停上浮。
 */
public class TaskListRenderer extends JPanel implements ListCellRenderer<Task> {

    /** 勾选/删除按钮的点击回调。 */
    public interface Actions {
        void onToggle(Task task);

        void onDelete(Task task);
    }

    private static final DateTimeFormatter CREATED_AT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final TaskRow row;

    public TaskListRenderer(Actions actions) {
        setLayout(new BorderLayout());
        setOpaque(false);
        row = new TaskRow(actions);
        add(row, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Task> list, Task value,
                                                  int index, boolean isSelected, boolean cellHasFocus) {
        row.setTask(value);
        row.setSelected(isSelected);
        return this;
    }

    /** 单张任务卡片。 */
    private static class TaskRow extends JPanel {
        private final Actions actions;
        private final CheckBox checkBox = new CheckBox();
        private final DeleteButton deleteButton = new DeleteButton();
        private Task task;
        private boolean selected;
        private boolean hover;

        TaskRow(Actions actions) {
            this.actions = actions;
            setLayout(new BorderLayout(AppTheme.GAP, 0));
            setOpaque(false);
            // 卡片左右留出内边距，上下留缝，让卡片彼此分离
            setBorder(BorderFactory.createEmptyBorder(4, AppTheme.PADDING + 6,
                    4, AppTheme.PADDING + 6));

            add(checkBox, BorderLayout.WEST);
            add(deleteButton, BorderLayout.EAST);

            checkBox.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (task != null) {
                        actions.onToggle(task);
                    }
                }
            });
            deleteButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (task != null) {
                        actions.onDelete(task);
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setTask(Task task) {
            this.task = task;
            checkBox.checked = task != null && task.done();
            repaint();
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int inset = 4;
            int cardX = inset;
            int cardY = hover ? 1 : 3;
            int cardW = getWidth() - inset * 2;
            int cardH = getHeight() - inset - (hover ? 0 : 2);
            int radius = AppTheme.CARD_RADIUS;

            // 投影（悬停更明显）
            if (selected || hover) {
                AppTheme.paintSoftShadow(g2, cardX, cardY + 1, cardW, cardH, radius);
            }

            boolean done = task != null && task.done();
            Color cardBg = selected ? AppTheme.SELECTION
                    : (hover ? AppTheme.HOVER : AppTheme.SURFACE);
            g2.setColor(cardBg);
            g2.fillRoundRect(cardX, cardY, cardW, cardH, radius, radius);
            g2.setColor(AppTheme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(cardX, cardY, cardW, cardH, radius, radius);

            // 左侧状态色条：未完成=主题蓝，完成=薄荷绿
            Color bar = done ? AppTheme.SUCCESS : AppTheme.ACCENT;
            g2.setColor(bar);
            g2.fillRoundRect(cardX, cardY + 10, 4, cardH - 20, 4, 4);

            if (task != null) {
                paintText(g2, cardX, cardY, cardW, cardH, done);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        private void paintText(Graphics2D g2, int cardX, int cardY, int cardW, int cardH, boolean done) {
            int textLeft = cardX + 16 + 22 + 14; // 卡片左内边距 + 复选框宽 + 间隔
            int textRight = cardW - 40;          // 给删除按钮留位置

            String title = task.title();
            g2.setFont(AppTheme.regular(15));
            FontMetrics fm = g2.getFontMetrics();
            int maxTitleW = Math.max(10, textRight - textLeft);
            String shown = ellipsize(title, fm, maxTitleW);

            int titleY = cardY + cardH / 2 - 3;
            g2.setColor(done ? AppTheme.TEXT_DONE : AppTheme.TEXT_PRIMARY);
            g2.drawString(shown, textLeft, titleY);

            if (done) {
                int w = fm.stringWidth(shown);
                int ly = titleY - fm.getAscent() / 2 - 1;
                g2.setColor(AppTheme.TEXT_DONE);
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(textLeft, ly, textLeft + w, ly);
            }

            // 副标题：创建时间
            g2.setFont(AppTheme.regular(11));
            g2.setColor(AppTheme.TEXT_TERTIARY);
            String sub = "创建于 " + task.createdAt().format(CREATED_AT);
            g2.drawString(sub, textLeft, titleY + 17);
        }

        private static String ellipsize(String s, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(s) <= maxWidth) {
                return s;
            }
            String ellipsis = "…";
            int ew = fm.stringWidth(ellipsis);
            int end = s.length();
            while (end > 0 && fm.stringWidth(s.substring(0, end)) + ew > maxWidth) {
                end--;
            }
            return s.substring(0, end) + ellipsis;
        }
    }

    /** 圆形复选框，勾选后填充主题色并画对勾。 */
    private static class CheckBox extends JPanel {
        boolean checked;
        private boolean hover;

        CheckBox() {
            setOpaque(false);
            setPreferredSize(new Dimension(24, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = 20;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            if (checked) {
                g2.setColor(AppTheme.SUCCESS);
                g2.fillOval(x, y, d, d);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 5, y + 10, x + 8, y + 14);
                g2.drawLine(x + 8, y + 14, x + 15, y + 7);
            } else {
                if (hover) {
                    g2.setColor(AppTheme.alpha(AppTheme.ACCENT, 40));
                    g2.fillOval(x - 2, y - 2, d + 4, d + 4);
                }
                g2.setColor(AppTheme.SURFACE);
                g2.fillOval(x, y, d, d);
                g2.setColor(hover ? AppTheme.ACCENT : AppTheme.BORDER_STRONG);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawOval(x, y, d, d);
            }
            g2.dispose();
        }
    }

    /** 右侧删除按钮：悬停才显现，默认淡灰，悬停转红并带浅红圆底。 */
    private static class DeleteButton extends JPanel {
        private boolean hover;

        DeleteButton() {
            setOpaque(false);
            setPreferredSize(new Dimension(28, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = 22;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            if (hover) {
                g2.setColor(AppTheme.DANGER_SOFT);
                g2.fillOval(x, y, d, d);
            }
            g2.setColor(hover ? AppTheme.DANGER : AppTheme.TEXT_TERTIARY);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int p = 7;
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g2.drawLine(cx - p / 2, cy - p / 2, cx + p / 2, cy + p / 2);
            g2.drawLine(cx + p / 2, cy - p / 2, cx - p / 2, cy + p / 2);
            g2.dispose();
        }
    }
}
