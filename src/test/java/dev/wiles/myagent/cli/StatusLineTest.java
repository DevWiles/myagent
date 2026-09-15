package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class StatusLineTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String screen() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("非交互环境不画动画——否则日志里全是刷屏字符")
    void silentWhenNotAnimated() {
        StatusLine status = new StatusLine(out, false);
        status.start("思考中…");
        status.stop();

        assertEquals("", screen());
        assertFalse(status.isRunning());
    }

    @Test
    @DisplayName("动画模式下会把状态文字打出来")
    @Timeout(5)
    void showsText() throws Exception {
        StatusLine status = new StatusLine(out, true);
        status.start("思考中…");
        Thread.sleep(250);
        status.stop();

        assertTrue(screen().contains("思考中…"));
    }

    @Test
    @DisplayName("stop 之后把这行擦掉，后面的正文才不会和它挤在一起")
    @Timeout(5)
    void clearsOnStop() throws Exception {
        StatusLine status = new StatusLine(out, true);
        status.start("思考中…");
        Thread.sleep(150);
        status.stop();

        assertTrue(screen().endsWith(Ansi.clearLine() + Ansi.showCursor()),
                "停下时最后一件事应当是清行并恢复光标");
        assertFalse(status.isRunning());
    }

    @Test
    @DisplayName("stop 返回后动画线程必须已经死透——否则它会把主线程打印的正文擦掉")
    @Timeout(10)
    void stopWaitsForAnimationToDie() throws Exception {
        StatusLine status = new StatusLine(out, true);
        status.start("思考中…");
        Thread.sleep(250);          // 让它转起来

        status.stop();
        int sizeRightAfterStop = captured.size();

        // 动画间隔是 100ms，这段时间足够它再画好几帧。
        // 只要还有一个字节冒出来，就说明主线程接下来打印的正文会被 clearLine 抹掉。
        Thread.sleep(400);

        assertEquals(sizeRightAfterStop, captured.size(),
                "stop 之后动画线程还在画，屏幕上什么都留不下");
    }

    @Test
    @DisplayName("stop 之后打印的正文要原样留在输出里")
    @Timeout(10)
    void outputAfterStopSurvives() throws Exception {
        StatusLine status = new StatusLine(out, true);
        status.start("思考中…");
        Thread.sleep(250);
        status.stop();

        out.println("这是模型的回答");
        Thread.sleep(400);

        assertTrue(screen().endsWith("这是模型的回答" + System.lineSeparator()),
                "正文被动画线程覆盖了");
    }

    @Test
    @DisplayName("重复 stop 不出错")
    void stopIsIdempotent() {
        StatusLine status = new StatusLine(out, true);
        status.stop();
        status.stop();
        assertFalse(status.isRunning());
    }

    @Test
    @DisplayName("换状态文字不会重启计时，也不会多起一个线程")
    @Timeout(5)
    void updateKeepsRunning() throws Exception {
        StatusLine status = new StatusLine(out, true);
        status.start("思考中…");
        Thread.sleep(120);
        status.start("执行 read_file…");     // 重复 start 等同于改文字
        Thread.sleep(120);
        status.stop();

        String screen = screen();
        assertTrue(screen.contains("思考中…"));
        assertTrue(screen.contains("执行 read_file…"));
    }
}
