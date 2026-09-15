package dev.wiles.myagent.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带行编辑、历史和补全的输入读取器。
 *
 * raw 模式下终端把每个按键的字节直接丢给我们，回显和光标全得自己管。
 * 所以这个类做的事就是：维护一个字符缓冲区和光标位置，
 * 按收到的字节改缓冲区，然后把整行重画一遍。
 *
 * 三个容易踩的坑，代码里都处理了：
 *   1. 中文是多字节的，读到 0x80 以上的字节要继续读够再解码，否则输入中文全是乱码
 *   2. 一个汉字占两列，光标位置得按显示宽度算而不是字符个数，否则中英混输时光标会飘
 *   3. 非终端环境（管道、重定向）拿不到 raw 模式，必须退化成按行读，否则整个程序没法用
 *
 * 已知限制：输入超过一行宽度时重画会错乱，多行输入留待以后。
 */
public final class LineReader {

    /** 把输入行画到屏幕上。全屏模式下整屏都归它管。 */
    @FunctionalInterface
    public interface ScreenRenderer {
        void render(String prompt, String input, int cursor);
    }

    /**
     * 空行上按了 Ctrl-C。
     *
     * 为什么要抛出去而不是自己消化掉：我们用 -isig 关掉了终端的信号处理，
     * Ctrl-C 不再产生 SIGINT，也就不会有人替我们结束进程了。
     * 于是「按 Ctrl-C 退出」这条所有人都指望着的退路，必须由程序自己实现。
     */
    public static final class Interrupted extends RuntimeException {
        public Interrupted() {
            super("用户中断", null, false, false);
        }
    }

    // 控制字符
    private static final int CTRL_C = 3;
    private static final int CTRL_D = 4;
    private static final int CTRL_A = 1;
    private static final int CTRL_E = 5;
    private static final int CTRL_K = 11;
    private static final int CTRL_U = 21;
    private static final int CTRL_W = 23;
    private static final int TAB = 9;
    /** Ctrl-O：展开最近一轮的过程详情。 */
    private static final int CTRL_O = 15;
    /** 全屏模式下方向键用来滚内容，历史改走 Ctrl-P / Ctrl-N。 */
    private static final int CTRL_P = 16;
    private static final int CTRL_N = 14;
    private static final int ENTER = 13;
    private static final int NEWLINE = 10;
    private static final int BACKSPACE = 127;
    private static final int BACKSPACE_ALT = 8;

    private static final int MAX_HISTORY = 500;

    private final InputStream in;
    private final PrintStream out;
    private final boolean raw;

    private final List<String> history = new ArrayList<>();
    private List<String> completions = List.of();
    private Path historyFile;
    private Runnable expandAction;
    /** 全屏模式下由外部负责画整屏；为 null 时按老办法自己重画一行。 */
    private ScreenRenderer renderer;
    /** 全屏模式下方向键的去处：正数往下、负数往上。 */
    private IntConsumer scrollAction;
    private IntConsumer clickAction;
    private boolean mouseEnabled;
    /** 输入行此刻在屏幕的第几行（1 起）。点击坐标要靠它换算。 */
    private int inputScreenRow = -1;

    private InputFrame frame = InputFrame.none();
    /** 整行宽度，提交后铺底色要铺满这么宽；0 表示不知道宽度。 */
    private int lineWidth;
    private StringBuilder buffer = new StringBuilder();
    private int cursor;                 // 光标在 buffer 中的字符下标
    private int historyIndex;           // 当前浏览到历史的第几条
    private String stashedLine = "";    // 翻历史前正在编辑的内容

    public LineReader(InputStream in, PrintStream out, boolean raw) {
        this.in = in;
        this.out = out;
        this.raw = raw;
    }

    /** Tab 补全的候选项，目前是斜杠命令。 */
    public LineReader completions(List<String> values) {
        this.completions = List.copyOf(values);
        return this;
    }

    /**
     * 按 Ctrl-O 时干什么。
     *
     * 典型用途是把刚才被收起来的过程详情重新打一遍——终端做不到
     * 「点一下原地展开」，能做的是把内容追加打印在下面。
     */
    public LineReader onExpand(Runnable action) {
        this.expandAction = action;
        return this;
    }

    /**
     * 交出渲染权：有它在，行编辑只管维护缓冲区和光标，画面由外部整屏重绘。
     * 全屏模式用这个。
     */
    public LineReader renderer(ScreenRenderer renderer) {
        this.renderer = renderer;
        return this;
    }

    /**
     * 方向键上下改成滚动内容。
     *
     * 全屏模式下必须这么接：Alt Scroll 把滚轮翻译成方向键送进来，
     * 程序分不出「用户按了方向键」和「用户滚了滚轮」——既然滚轮更常用，
     * 方向键就让给滚动，翻历史改用 Ctrl-P / Ctrl-N。
     */
    public LineReader onScroll(IntConsumer action) {
        this.scrollAction = action;
        return this;
    }

    /**
     * 开启鼠标点击。回调收到的是「点击位置在输入行上方第几行」——
     * 0 表示点的就是输入行，1 表示上面一行，依此类推。
     *
     * 换算要用到输入行当前的屏幕行号，这个值靠 DSR 向终端问出来，
     * 每次重新铺开输入框时问一次（那时用户还没开始敲，读回复不会和输入抢）。
     */
    public LineReader onClick(IntConsumer action) {
        this.clickAction = action;
        this.mouseEnabled = action != null;
        return this;
    }

    /** 告诉它终端有多宽，提交后那一行的底色要铺满整行才显眼。 */
    public LineReader width(int width) {
        this.lineWidth = width;
        return this;
    }

    /** 历史文件，跨进程保留。读写失败都不影响使用。 */
    public LineReader historyFile(Path file) {
        this.historyFile = file;
        try {
            if (Files.isReadable(file)) {
                history.addAll(Files.readAllLines(file));
                trimHistory();
            }
        } catch (IOException ignored) {
            // 历史读不出来不是什么大事
        }
        return this;
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    /**
     * 读一行。
     *
     * @return 用户输入的内容；按 Ctrl-D（空行）或输入结束时返回 null
     */
    public String readLine(String prompt) {
        return readLine(prompt, InputFrame.none());
    }

    /**
     * 读一行，并在输入行上下画出边框。
     *
     * 画法是「先铺开，再回去」：一上来就把上边线、输入行、下边线、状态行全部打出来，
     * 然后用光标上移回到输入行。之后每次按键只重画输入行那一行——
     * 上下的装饰已经在屏幕上了，不用动。
     *
     * 提交时再把光标移到最下面一行之后换行，这样装饰会原样留在历史记录里，
     * 顺带充当每一轮的分隔。
     */
    public String readLine(String prompt, InputFrame frame) {
        if (!raw) {
            return readLineSimple(prompt);
        }
        this.frame = frame;
        if (mouseEnabled) {
            out.print(Ansi.mouseOn());
        }
        buffer = new StringBuilder();
        cursor = 0;
        historyIndex = history.size();
        stashedLine = "";
        drawFrame(prompt);

        try {
            while (true) {
                int b = in.read();
                if (b == -1) {
                    out.println();
                    return null;
                }
                switch (b) {
                    case ENTER, NEWLINE -> {
                        collapseFrame(submittedLine(prompt));
                        out.flush();
                        String line = buffer.toString();
                        remember(line);
                        return line;
                    }
                    case CTRL_C -> {
                        if (buffer.length() == 0) {
                            // 空行上的 Ctrl-C 交给调用方决定（通常是「再按一次就退出」）
                            collapseFrame("^C");
                            throw new Interrupted();
                        }
                        // 有内容时和 shell 一致：清掉这一行重来。
                        // 注意只重画输入行，不能再铺一次框，否则屏幕上会多出一个框。
                        buffer = new StringBuilder();
                        cursor = 0;
                        redraw(prompt);
                    }
                    case CTRL_D -> {
                        if (buffer.length() == 0) {
                            collapseFrame(prompt.isEmpty() ? "" : prompt);
                            return null;                  // 空行上的 Ctrl-D 才是退出
                        }
                        if (cursor < buffer.length()) {
                            buffer.deleteCharAt(cursor);  // 否则等同于删除光标处的字符
                            redraw(prompt);
                        }
                    }
                    case BACKSPACE, BACKSPACE_ALT -> {
                        if (cursor > 0) {
                            buffer.deleteCharAt(--cursor);
                            redraw(prompt);
                        }
                    }
                    case CTRL_P -> navigateHistory(-1, prompt);
                    case CTRL_N -> navigateHistory(1, prompt);
                    case CTRL_A -> {
                        cursor = 0;
                        redraw(prompt);
                    }
                    case CTRL_E -> {
                        cursor = buffer.length();
                        redraw(prompt);
                    }
                    case CTRL_U -> {
                        buffer.delete(0, cursor);
                        cursor = 0;
                        redraw(prompt);
                    }
                    case CTRL_K -> {
                        buffer.delete(cursor, buffer.length());
                        redraw(prompt);
                    }
                    case CTRL_W -> {
                        deletePreviousWord();
                        redraw(prompt);
                    }
                    case TAB -> {
                        complete();
                        redraw(prompt);
                    }
                    case CTRL_O -> {
                        if (expandAction != null) {
                            // 先把输入框整个抹掉，让详情从干净的位置开始打，
                            // 打完再把框原样铺回来——输入到一半的内容不受影响
                            eraseFrame();
                            expandAction.run();
                            drawFrame(prompt);
                        }
                    }
                    case Ansi.ESC -> handleEscape(prompt);
                    default -> {
                        if (b >= 32) {
                            insert(decodeUtf8(b));
                            redraw(prompt);
                        }
                        // 其余控制字符直接忽略
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 把一个起始字节还原成完整字符。
     *
     * raw 模式读到的是字节流，一个汉字是 3 个字节。
     * 只取第一个字节就当字符用的话，中文输入全是乱码。
     */
    private String decodeUtf8(int first) throws IOException {
        if (first < 0x80) {
            return String.valueOf((char) first);
        }
        int extra = first >= 0xF0 ? 3 : first >= 0xE0 ? 2 : 1;
        byte[] bytes = new byte[extra + 1];
        bytes[0] = (byte) first;
        for (int i = 1; i <= extra; i++) {
            int next = in.read();
            if (next == -1) {
                break;
            }
            bytes[i] = (byte) next;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 方向键、Home/End、Delete 都是以 ESC 开头的多字节序列。 */
    private void handleEscape(String prompt) throws IOException {
        int second = in.read();
        if (second != '[' && second != 'O') {
            return;
        }
        int third = in.read();
        if (third == '<') {
            handleMouse(prompt);          // SGR 格式的鼠标事件
            return;
        }
        switch (third) {
            case 'A' -> {
                if (scrollAction != null) {
                    scrollAction.accept(-1);       // 滚轮上滚 / 方向键上
                    redraw(prompt);
                } else {
                    navigateHistory(-1, prompt);
                }
            }
            case 'B' -> {
                if (scrollAction != null) {
                    scrollAction.accept(1);
                    redraw(prompt);
                } else {
                    navigateHistory(1, prompt);
                }
            }
            case 'C' -> {
                if (cursor < buffer.length()) {
                    cursor++;
                    redraw(prompt);
                }
            }
            case 'D' -> {
                if (cursor > 0) {
                    cursor--;
                    redraw(prompt);
                }
            }
            case 'H' -> {
                cursor = 0;
                redraw(prompt);
            }
            case 'F' -> {
                cursor = buffer.length();
                redraw(prompt);
            }
            case '3' -> {
                in.read();                       // 吃掉结尾的 '~'
                if (cursor < buffer.length()) {
                    buffer.deleteCharAt(cursor);
                    redraw(prompt);
                }
            }
            default -> {
                // 其它序列忽略
            }
        }
    }

    /**
     * 处理一次鼠标事件。
     *
     * SGR 格式：ESC[&lt;按键;列;行M（按下）或 m（松开）。
     * 只认左键按下，其它一律忽略——滚轮和拖拽也会走这条路，
     * 不过滤的话滚一下屏幕就触发一次展开。
     */
    private void handleMouse(String prompt) throws IOException {
        StringBuilder raw = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != 'M' && b != 'm') {
            raw.append((char) b);
        }
        if (b != 'M' || clickAction == null || inputScreenRow <= 0) {
            return;
        }
        String[] parts = raw.toString().split(";");
        if (parts.length < 3) {
            return;
        }
        try {
            if (Integer.parseInt(parts[0]) != 0) {
                return;                   // 0 才是左键，滚轮是 64/65
            }
            int clickedRow = Integer.parseInt(parts[2]);
            int linesAbove = inputScreenRow - clickedRow;
            if (linesAbove < 0) {
                return;                   // 点在输入框下方，没什么可展开的
            }
            // 和 Ctrl-O 一样：先把框抹掉，让回调从干净的位置开始画，画完再铺回来
            eraseFrame();
            clickAction.accept(linesAbove);
            drawFrame(prompt);
        } catch (NumberFormatException ignored) {
            // 坐标解析不了就当没点
        }
    }

    /**
     * 问终端「光标现在在第几行」（DSR）。
     *
     * 回复是从 stdin 读回来的，所以只能在确定用户没在输入的时候问——
     * 也就是刚铺开输入框那一刻。带超时，终端不认这个序列时不能把程序卡死。
     */
    private int queryCursorRow() {
        out.print(Ansi.queryCursor());
        out.flush();
        StringBuilder reply = new StringBuilder();
        long deadline = System.currentTimeMillis() + 250;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int b = in.read();
                    if (b == 'R') {
                        break;
                    }
                    reply.append((char) b);
                } else {
                    Thread.sleep(5);
                }
            }
        } catch (Exception e) {
            return -1;
        }
        Matcher m = Pattern.compile("\\[(\\d+);(\\d+)").matcher(reply);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private void navigateHistory(int delta, String prompt) {
        if (history.isEmpty()) {
            return;
        }
        if (historyIndex == history.size()) {
            stashedLine = buffer.toString();     // 记住正在编辑的内容，翻回来时要还原
        }
        int target = historyIndex + delta;
        if (target < 0 || target > history.size()) {
            return;
        }
        historyIndex = target;
        String line = target == history.size() ? stashedLine : history.get(target);
        buffer = new StringBuilder(line);
        cursor = buffer.length();
        redraw(prompt);
    }

    private void deletePreviousWord() {
        int end = cursor;
        while (cursor > 0 && buffer.charAt(cursor - 1) == ' ') {
            cursor--;
        }
        while (cursor > 0 && buffer.charAt(cursor - 1) != ' ') {
            cursor--;
        }
        buffer.delete(cursor, end);
    }

    private void insert(String text) {
        buffer.insert(cursor, text);
        cursor += text.length();
    }

    /** 只补全斜杠命令：唯一匹配就直接补上，多个则列出来让用户自己选。 */
    private void complete() {
        String prefix = buffer.substring(0, cursor);
        if (!prefix.startsWith("/")) {
            return;
        }
        List<String> matches = completions.stream().filter(c -> c.startsWith(prefix)).toList();
        if (matches.isEmpty()) {
            return;
        }
        if (matches.size() == 1) {
            buffer = new StringBuilder(matches.get(0));
            cursor = buffer.length();
            return;
        }
        out.println();
        out.println(String.join("   ", matches));
    }

    /** 首次铺开整个框，光标停在输入行。 */
    private void drawFrame(String prompt) {
        if (renderer != null) {
            redraw(prompt);          // 全屏模式的布局是固定的，不需要铺开什么
            return;
        }
        inputScreenRow = -1;
        if (frame.isEmpty()) {
            redraw(prompt);
            return;
        }
        StringBuilder sb = new StringBuilder(Ansi.clearLine());
        if (frame.top() != null) {
            sb.append(frame.top()).append('\n');
        }
        sb.append(inputLine(prompt));
        if (frame.bottom() != null) {
            sb.append('\n').append(frame.bottom());
        }
        if (frame.below() != null) {
            sb.append('\n').append(frame.below());
        }
        // 回到输入行。装饰已经铺在屏幕上了，后面每次按键只需重画这一行。
        sb.append(Ansi.up(frame.linesBelow()));
        out.print(sb);
        redraw(prompt);
        if (mouseEnabled) {
            inputScreenRow = queryCursorRow();
        }
    }

    /**
     * 提交时把框收起来，只留下一行输入内容。
     *
     * 边框是「正在输入」这个状态的提示，输入完就没意义了——
     * 留在历史里只会让屏幕被一堆横线切得七零八落。
     * 所以擦掉上下边线和状态行，把内容重画在原来上边线的位置，四行塌成一行。
     */
    /** 把输入框从屏幕上抹掉，光标停在框原来的起始位置。 */
    private void eraseFrame() {
        if (renderer != null) {
            return;
        }
        if (frame.isEmpty()) {
            return;
        }
        if (frame.top() != null) {
            out.print(Ansi.up(1));
        }
        out.print(Ansi.toLineStart() + Ansi.clearToEnd());
    }

    private void collapseFrame(String finalLine) {
        if (renderer != null) {
            return;                  // 全屏模式下输入框是常驻的，没有收起这回事
        }
        if (frame.isEmpty()) {
            out.println();
            return;
        }
        // 光标此刻在输入行。先退到上边线那一行，然后一次性清掉从这里
        // 到屏幕末尾的所有内容——上边线、输入行、下边线、状态行全在里面。
        //
        // 之前是「逐行下移擦、再逐行上移擦」，十来个控制序列换来同样的效果，
        // 还容易被终端的各种启发式盯上（macOS Terminal.app 会给这样的行
        // 画上「提示符标记」）。ED 一条就够。
        if (frame.top() != null) {
            out.print(Ansi.up(1));
        }
        // 上移只改行不改列，光标此刻还停在原来那一列上。
        // ED 是从光标位置往后清的，不先回到行首的话，
        // 光标左边那半截（上边线的左半、底色块）会原样留在屏幕上。
        out.print(Ansi.toLineStart() + Ansi.clearToEnd());
        out.println(finalLine);
    }

    /**
     * 提交后留在屏幕上的那一行：整行铺底色，一眼能认出哪句是自己说的。
     * 底色要铺满整行宽度才显眼，所以末尾补空格。
     */
    private String submittedLine(String prompt) {
        String text = prompt + buffer;
        // 补到 width-1 而不是 width：写满最后一列会让光标停在自动换行的临界点上，
        // 终端在这个状态下对后续控制序列的处理各家不一，容易出怪东西。
        int pad = Math.max(0, lineWidth - 1 - Ansi.displayWidth(text));
        // 注意不能在里面再套 Ansi.cyan()：那串颜色码是以「重置全部属性」收尾的，
        // 会把外层的背景色一起关掉，结果只有提示符那一小段有底色。
        return Ansi.highlight(text + " ".repeat(pad));
    }

    /** 输入行长什么样。首次铺开和之后每次重画共用，免得闪。 */
    private String inputLine(String prompt) {
        return Ansi.cyan(prompt) + buffer;
    }

    /** 画出当前状态。全屏模式下交给外部整屏重绘，否则只重画输入行。 */
    private void redraw(String prompt) {
        if (renderer != null) {
            renderer.render(prompt, buffer.toString(), cursor);
            return;
        }
        out.print(Ansi.clearLine() + inputLine(prompt));
        // 光标此刻停在内容末尾，按显示宽度退回到该在的位置（汉字占两列）
        out.print(Ansi.left(Ansi.displayWidth(buffer.substring(cursor))));
        out.flush();
    }

    private void remember(String line) {
        if (line.isBlank()) {
            return;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).equals(line)) {
            return;                              // 连续重复的不重复记
        }
        history.add(line);
        trimHistory();
        saveHistory();
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private void saveHistory() {
        if (historyFile == null) {
            return;
        }
        try {
            Files.createDirectories(historyFile.getParent());
            Files.write(historyFile, history);
        } catch (IOException ignored) {
            // 存不下就算了，不能因为这个打断用户
        }
    }

    /** 非终端环境（管道、重定向）的退路：老老实实按行读。 */
    private String readLineSimple(String prompt) {
        out.print(prompt);
        out.flush();
        StringBuilder line = new StringBuilder();
        try {
            int b;
            while ((b = in.read()) != -1) {
                if (b == NEWLINE) {
                    out.println();      // 这个模式下没有回显，得自己补个换行
                    return line.toString();
                }
                if (b != ENTER) {
                    line.append(decodeUtf8(b));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return line.length() == 0 ? null : line.toString();
    }
}
