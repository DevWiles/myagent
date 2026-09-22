package dev.wiles.myapp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoListTest {

    @Test
    void addAssignsIncrementingIds() {
        TodoList list = new TodoList();
        Task a = list.add("写代码");
        Task b = list.add("写测试");
        assertEquals(1, a.id());
        assertEquals(2, b.id());
        assertEquals(2, list.size());
    }

    @Test
    void addRejectsBlankTitle() {
        TodoList list = new TodoList();
        assertThrows(IllegalArgumentException.class, () -> list.add("   "));
    }

    @Test
    void toggleFlipsDone() {
        TodoList list = new TodoList();
        Task t = list.add("做点事");
        assertFalse(t.done());
        list.toggle(t.id());
        assertTrue(list.find(t.id()).orElseThrow().done());
        list.toggle(t.id());
        assertFalse(list.find(t.id()).orElseThrow().done());
    }

    @Test
    void removeAndRename() {
        TodoList list = new TodoList();
        Task t = list.add("旧标题");
        assertTrue(list.rename(t.id(), "新标题"));
        assertEquals("新标题", list.find(t.id()).orElseThrow().title());
        assertTrue(list.remove(t.id()));
        assertTrue(list.isEmpty());
        assertFalse(list.remove(t.id()));
    }

    @Test
    void sortedPutsPendingFirst() {
        TodoList list = new TodoList();
        Task a = list.add("一");
        Task b = list.add("二");
        list.toggle(a.id());
        List<Task> sorted = list.sorted();
        assertEquals(b.id(), sorted.get(0).id());
        assertEquals(a.id(), sorted.get(1).id());
    }
}
