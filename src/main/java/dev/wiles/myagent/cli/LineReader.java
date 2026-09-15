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
        if (!raw) {
            return readLineSimple(prompt);
        }
        buffer = new StringBuilder();
        cursor = 0;
        historyIndex = history.size();
        stashedLine = "";
        redraw(prompt);

        try {
            while (true) {
                int b = in.read();
                if (b == -1) {
                    out.println();
                    return null;
                }
                switch (b) {
                    case ENTER, NEWLINE -> {
                        out.println();
                        out.flush();
                        String line = buffer.toString();
                        remember(line);
                        return line;
                    }
                    case CTRL_C -> {
                        out.println("^C");
                        if (buffer.length() == 0) {
                            // 空行上的 Ctrl-C 交给调用方决定（通常是「再按一次就退出」）
                            throw new Interrupted();
                        }
                        // 有内容时和 shell 一致：放弃当前这行，另起一行
                        buffer = new StringBuilder();
                        cursor = 0;
                        redraw(prompt);
                    }
                    case CTRL_D -> {
                        if (buffer.length() == 0) {
                            out.println();
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
        switch (third) {
            case 'A' -> navigateHistory(-1, prompt);
            case 'B' -> navigateHistory(1, prompt);
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

    private void redraw(String prompt) {
        out.print(Ansi.clearLine() + prompt + buffer);
        int tail = Ansi.displayWidth(buffer.substring(cursor));
        out.print(Ansi.left(tail));
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
