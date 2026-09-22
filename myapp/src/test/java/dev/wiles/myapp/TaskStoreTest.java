package dev.wiles.myapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {

    @Test
    void saveThenLoadRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tasks.tsv");
        TaskStore store = new TaskStore(file);

        TodoList list = new TodoList();
        Task a = list.add("买牛奶");
        Task b = list.add("写文档");
        list.toggle(b.id());
        store.save(list.all());

        assertTrue(file.toFile().exists());

        TodoList reloaded = new TodoList();
        reloaded.restore(store.load());
        assertEquals(2, reloaded.size());
        assertEquals("买牛奶", reloaded.find(a.id()).orElseThrow().title());
        assertTrue(reloaded.find(b.id()).orElseThrow().done());

        // 恢复后新增 id 不应冲突
        Task c = reloaded.add("新任务");
        assertEquals(3, c.id());
    }

    @Test
    void loadMissingFileReturnsEmpty(@TempDir Path dir) throws IOException {
        TaskStore store = new TaskStore(dir.resolve("nope.tsv"));
        List<Task> loaded = store.load();
        assertTrue(loaded.isEmpty());
    }
}
