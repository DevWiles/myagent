package dev.wiles.myapp.ui;

import dev.wiles.myapp.Task;
import dev.wiles.myapp.TaskStore;
import dev.wiles.myapp.TodoList;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * myapp 的图形界面主窗口。卡片 + 渐变 banner 风格，含统计卡片、进度条与筛选。
 */
public class TodoFrame extends JFrame implements TaskListRenderer.Actions {

    private enum Filter {
        ALL("全部"), PENDING("进行中"), DONE("已完成");

        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private final TodoList list;
    private final TaskStore store;

    private final DefaultListModel<Task> model = new DefaultListModel<>();
    private final JList<Task> taskList = new JList<>(model);
    private final JTextField input = new JTextField();
    private final JLabel status = new JLabel();

    // 统计数字与进度
    private final StatCard totalCard = new StatCard("全部任务", AppTheme.ACCENT);
    private final StatCard pendingCard = new StatCard("进行中", AppTheme.WARNING);
    private final StatCard doneCard = new StatCard("已完成", AppTheme.SUCCESS);
    private final ProgressBar progressBar = new ProgressBar();

    // 筛选标签
    private final List<FilterChip> chips = new ArrayList<>();
    private Filter filter = Filter.ALL;

    // 空状态与列表用 CardLayout 切换
    private final CardLayout listCards = new CardLayout();
    private final JPanel listHolder = new JPanel(listCards);

    public TodoFrame(TodoList list, TaskStore store) {
        super("myapp · 待办清单");
        this.list = list;
        this.store = store;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(480, 560));
        setSize(600, 720);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(AppTheme.BACKGROUND);
        root.add(buildBanner(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        setContentPane(root);

        reload();
    }

    // --- 顶部渐变 banner -----------------------------------------------------

    private JPanel buildBanner() {
        JPanel banner = new JPanel(new BorderLayout(0, 14)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(AppTheme.verticalGradient(AppTheme.ACCENT_LIGHT, AppTheme.ACCENT_DEEP,
                        getHeight()));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        banner.setOpaque(false);
        banner.setBorder(BorderFactory.createEmptyBorder(22, AppTheme.PADDING + 6,
                22, AppTheme.PADDING + 6));

        // 标题行
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JLabel title = new JLabel("我的待办");
        title.setFont(AppTheme.bold(24));
        title.setForeground(AppTheme.TEXT_ON_ACCENT);
        titleRow.add(title, BorderLayout.WEST);
        JLabel subtitle = new JLabel("专注当下，逐项击破");
        subtitle.setFont(AppTheme.regular(12));
        subtitle.setForeground(AppTheme.alpha(Color.WHITE, 200));
        subtitle.setHorizontalAlignment(SwingConstants.RIGHT);
        titleRow.add(subtitle, BorderLayout.EAST);
        banner.add(titleRow, BorderLayout.NORTH);

        // 进度条
        banner.add(progressBar, BorderLayout.CENTER);

        return banner;
    }

    // --- 主体 ---------------------------------------------------------------

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, AppTheme.GAP));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(AppTheme.GAP, AppTheme.PADDING,
                AppTheme.PADDING, AppTheme.PADDING));

        body.add(buildTopArea(), BorderLayout.NORTH);
        body.add(buildListArea(), BorderLayout.CENTER);
        body.add(buildFooter(), BorderLayout.SOUTH);
        return body;
    }

    private JPanel buildTopArea() {
        JPanel top = new JPanel(new BorderLayout(0, AppTheme.GAP));
        top.setOpaque(false);

        // 统计卡片：三栏
        JPanel stats = new JPanel(new GridLayout(1, 3, AppTheme.GAP, 0));
        stats.setOpaque(false);
        stats.add(totalCard);
        stats.add(pendingCard);
        stats.add(doneCard);

        // 输入行 + 筛选
        JPanel inputRow = new JPanel(new BorderLayout(AppTheme.GAP, 0));
        inputRow.setOpaque(false);

        RoundedInputField roundedInput = new RoundedInputField(input);
        roundedInput.setPreferredSize(new Dimension(0, 44));
        input.setFont(AppTheme.regular(15));
        input.setOpaque(false);
        input.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        input.setForeground(AppTheme.TEXT_PRIMARY);
        input.putClientProperty("JTextField.placeholderText", "添加一条任务，回车确认…");
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    addCurrentInput();
                }
            }
        });

        JButton addButton = accentButton("＋ 添加");
        addButton.setPreferredSize(new Dimension(100, 44));
        addButton.addActionListener(e -> addCurrentInput());

        inputRow.add(roundedInput, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(AppTheme.GAP, 0));
        right.setOpaque(false);
        right.add(addButton, BorderLayout.EAST);
        inputRow.add(right, BorderLayout.EAST);

        // 筛选标签行
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterRow.setOpaque(false);
        for (Filter f : Filter.values()) {
            FilterChip chip = new FilterChip(f.label, f == filter);
            chip.addActionListener(e -> {
                filter = f;
                for (FilterChip c : chips) {
                    c.setActive(c.filterName.equals(f.label));
                }
                reload();
            });
            chips.add(chip);
            filterRow.add(chip);
        }

        JPanel south = new JPanel(new BorderLayout(0, AppTheme.GAP));
        south.setOpaque(false);
        south.add(inputRow, BorderLayout.NORTH);
        south.add(filterRow, BorderLayout.CENTER);

        top.add(stats, BorderLayout.NORTH);
        top.add(south, BorderLayout.CENTER);
        return top;
    }

    private JPanel buildListArea() {
        taskList.setCellRenderer(new TaskListRenderer(this));
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskList.setBackground(AppTheme.BACKGROUND);
        taskList.setFixedCellHeight(AppTheme.ROW_HEIGHT);
        taskList.setBorder(BorderFactory.createEmptyBorder());
        taskList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    Task t = taskList.getSelectedValue();
                    if (t != null) {
                        onToggle(t);
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(taskList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(AppTheme.BACKGROUND);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));

        listHolder.setOpaque(false);
        listHolder.add(scroll, "list");
        listHolder.add(buildEmptyState(), "empty");

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(listHolder, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildEmptyState() {
        JPanel empty = new JPanel(new BorderLayout());
        empty.setOpaque(false);

        JLabel icon = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.setColor(AppTheme.SUCCESS_SOFT);
                g2.fillOval(cx - 34, cy - 34, 68, 68);
                g2.setColor(AppTheme.SUCCESS);
                g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - 15, cy + 1, cx - 4, cy + 12);
                g2.drawLine(cx - 4, cy + 12, cx + 16, cy - 12);
                g2.dispose();
            }
        };
        icon.setPreferredSize(new Dimension(0, 90));

        JLabel text = new JLabel("这里空空如也", SwingConstants.CENTER);
        text.setFont(AppTheme.bold(16));
        text.setForeground(AppTheme.TEXT_SECONDARY);

        JLabel hint = new JLabel("在上面输入框写下第一件事吧", SwingConstants.CENTER);
        hint.setFont(AppTheme.regular(12));
        hint.setForeground(AppTheme.TEXT_TERTIARY);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        text.setAlignmentX(CENTER_ALIGNMENT);
        hint.setAlignmentX(CENTER_ALIGNMENT);
        center.add(Box.createVerticalGlue());
        center.add(icon);
        center.add(Box.createVerticalStrut(8));
        center.add(text);
        center.add(Box.createVerticalStrut(4));
        center.add(hint);
        center.add(Box.createVerticalGlue());

        empty.add(center, BorderLayout.CENTER);
        return empty;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        status.setFont(AppTheme.regular(12));
        status.setForeground(AppTheme.TEXT_SECONDARY);
        footer.add(status, BorderLayout.WEST);

        JButton clearButton = linkButton("清空全部");
        clearButton.addActionListener(e -> clearAll());
        footer.add(clearButton, BorderLayout.EAST);
        return footer;
    }

    // --- 控件 ---------------------------------------------------------------

    private JButton accentButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = AppTheme.ACCENT;
                if (getModel().isPressed()) {
                    bg = AppTheme.ACCENT_PRESSED;
                } else if (getModel().isRollover()) {
                    bg = AppTheme.ACCENT_HOVER;
                }
                AppTheme.paintSoftShadow(g2, 0, 2, getWidth(), getHeight() - 2,
                        AppTheme.CARD_RADIUS);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight() - 2, AppTheme.CARD_RADIUS,
                        AppTheme.CARD_RADIUS);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(AppTheme.bold(14));
        button.setForeground(AppTheme.TEXT_ON_ACCENT);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JButton linkButton(String text) {
        JButton button = new JButton(text);
        button.setFont(AppTheme.regular(12));
        button.setForeground(AppTheme.TEXT_SECONDARY);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setHorizontalAlignment(SwingConstants.RIGHT);
        return button;
    }

    // --- 行为 ---------------------------------------------------------------

    private void addCurrentInput() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        list.add(text);
        input.setText("");
        reload();
        persist();
        taskList.ensureIndexIsVisible(model.getSize() - 1);
    }

    @Override
    public void onToggle(Task task) {
        list.toggle(task.id());
        reload();
        persist();
    }

    @Override
    public void onDelete(Task task) {
        list.remove(task.id());
        reload();
        persist();
    }

    private void clearAll() {
        if (list.isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this, "确定要清空全部任务吗？",
                "确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            list.clear();
            reload();
            persist();
        }
    }

    /** 从 TodoList 重新灌入列表模型并刷新统计、进度与状态栏。 */
    private void reload() {
        Predicate<Task> pred = switch (filter) {
            case ALL -> t -> true;
            case PENDING -> t -> !t.done();
            case DONE -> Task::done;
        };

        model.clear();
        for (Task t : list.sorted()) {
            if (pred.test(t)) {
                model.addElement(t);
            }
        }

        int total = list.size();
        int pending = list.pending().size();
        int done = total - pending;

        totalCard.setValue(total);
        pendingCard.setValue(pending);
        doneCard.setValue(done);
        progressBar.setProgress(total == 0 ? 0 : (double) done / total);

        status.setText("共 " + total + " 条 · 未完成 " + pending + " 条 · 已完成 " + done + " 条");

        if (model.isEmpty()) {
            listCards.show(listHolder, "empty");
        } else {
            listCards.show(listHolder, "list");
        }
    }

    private void persist() {
        try {
            store.save(list.all());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "保存失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 供测试或外部注入任务列表。 */
    List<Task> currentTasks() {
        return java.util.Collections.list(model.elements());
    }

    // --- 内部小组件 ---------------------------------------------------------

    /** 统计卡片：大数字 + 小标题，顶部一条主题色。 */
    private static class StatCard extends JPanel {
        private final String title;
        private final Color tone;
        private int value;

        StatCard(String title, Color tone) {
            this.title = title;
            this.tone = tone;
            setOpaque(false);
            setPreferredSize(new Dimension(0, 76));
        }

        void setValue(int value) {
            this.value = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = AppTheme.CARD_RADIUS;
            g2.setColor(AppTheme.SURFACE);
            g2.fillRoundRect(0, 0, w, h, r, r);
            g2.setColor(AppTheme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);

            // 左侧色块
            g2.setColor(AppTheme.alpha(tone, 36));
            g2.fillRoundRect(14, 16, 6, h - 32, 6, 6);
            g2.setColor(tone);
            g2.fillRoundRect(14, 16, 6, h - 32, 6, 6);

            g2.setColor(AppTheme.TEXT_PRIMARY);
            g2.setFont(AppTheme.bold(24));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(String.valueOf(value), 34, h / 2 + fm.getAscent() / 2 - 2);

            g2.setColor(AppTheme.TEXT_SECONDARY);
            g2.setFont(AppTheme.regular(11));
            g2.drawString(title, 34, h - 12);
            g2.dispose();
        }
    }

    /** 顶部进度条：展示完成占比。 */
    private static class ProgressBar extends JPanel {
        private double progress;

        ProgressBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(0, 30));
        }

        void setProgress(double progress) {
            this.progress = Math.max(0, Math.min(1, progress));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int trackY = 18;
            int trackH = 8;

            g2.setColor(AppTheme.alpha(Color.WHITE, 60));
            g2.fillRoundRect(0, trackY, w, trackH, trackH, trackH);

            int fillW = (int) Math.round(w * progress);
            if (fillW > 0) {
                g2.setPaint(AppTheme.horizontalGradient(Color.WHITE,
                        AppTheme.mix(Color.WHITE, AppTheme.SUCCESS_SOFT, 0.4), Math.max(1, fillW)));
                g2.fillRoundRect(0, trackY, fillW, trackH, trackH, trackH);
            }

            g2.setColor(AppTheme.alpha(Color.WHITE, 220));
            g2.setFont(AppTheme.regular(11));
            g2.drawString("完成进度 " + Math.round(progress * 100) + "%", 0, 12);
            g2.dispose();
        }
    }

    /** 筛选标签（胶囊）。 */
    private static class FilterChip extends JButton {
        final String filterName;
        private boolean active;

        FilterChip(String name, boolean active) {
            super(name);
            this.filterName = name;
            this.active = active;
            setFont(AppTheme.regular(12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(74, 30));
        }

        void setActive(boolean active) {
            this.active = active;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            int r = h;
            if (active) {
                g2.setColor(AppTheme.ACCENT);
                g2.fillRoundRect(0, 0, getWidth(), h, r, r);
                setForeground(AppTheme.TEXT_ON_ACCENT);
            } else {
                g2.setColor(getModel().isRollover() ? AppTheme.HOVER : AppTheme.SURFACE);
                g2.fillRoundRect(0, 0, getWidth(), h, r, r);
                g2.setColor(AppTheme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, r, r);
                setForeground(AppTheme.TEXT_SECONDARY);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 给文本框套一个圆角白底容器。 */
    private static class RoundedInputField extends JPanel {
        RoundedInputField(JTextField field) {
            setLayout(new BorderLayout());
            setOpaque(false);
            field.setOpaque(false);
            add(field, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = 12;
            g2.setColor(AppTheme.SURFACE);
            g2.fillRoundRect(0, 0, w, h, r, r);
            g2.setColor(AppTheme.BORDER_STRONG);
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);
            g2.dispose();
        }
    }
}
