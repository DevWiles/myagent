package dev.wiles.myapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    private ByteArrayOutputStream buffer;
    private PrintStream out;
    private TodoList list;
    private TaskStore store;
    private Cli cli;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        buffer = new ByteArrayOutputStream();
        out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        list = new TodoList();
        store = new TaskStore(dir.resolve("tasks.tsv"));
        cli = new Cli(list, store, out);
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void addThenList() throws IOException {
        assertEquals(0, cli.run(new String[]{"add", "写", "测试"}));
        assertTrue(output().contains("已添加"));
        assertEquals(1, list.size());
        assertEquals("写 测试", list.all().get(0).title());

        buffer.reset();
        assertEquals(0, cli.run(new String[]{"list"}));
        assertTrue(output().contains("写 测试"));
        assertTrue(output().contains("未完成 1 条"));
    }

    @Test
    void doneAndRemovePersist() throws IOException {
        cli.run(new String[]{"add", "任务A"});
        assertEquals(0, cli.run(new String[]{"done", "1"}));
        assertTrue(output().contains("已完成"));

        buffer.reset();
        assertEquals(0, cli.run(new String[]{"rm", "1"}));
        assertTrue(list.isEmpty());
    }

    @Test
    void unknownIdReturnsErrorCode() throws IOException {
        assertEquals(2, cli.run(new String[]{"done", "99"}));
        assertTrue(output().contains("找不到任务"));
    }

    @Test
    void invalidIdReturnsErrorCode() throws IOException {
        assertEquals(2, cli.run(new String[]{"rm", "abc"}));
        assertTrue(output().contains("id 必须是数字"));
    }

    @Test
    void noArgsPrintsUsage() throws IOException {
        assertEquals(0, cli.run(new String[]{}));
        assertTrue(output().contains("用法"));
    }

    @Test
    void rename() throws IOException {
        cli.run(new String[]{"add", "旧"});
        buffer.reset();
        assertEquals(0, cli.run(new String[]{"rename", "1", "新", "名字"}));
        assertEquals("新 名字", list.find(1).orElseThrow().title());
    }
}
