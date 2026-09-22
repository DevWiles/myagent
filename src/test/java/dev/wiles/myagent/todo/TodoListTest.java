package dev.wiles.myagent.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 待办列表的增删改查。纯内存，没什么花样，但顺序和 id 归属错了用户会当场察觉。 */
class TodoListTest {

    @Test
    @DisplayName("添加任务会生成 id 并默认未完成")
    void addGeneratesIdAndDefaultsToPending() {
        TodoList list = new TodoList();
        Todo todo = list.add("买菜");
        assertNotNull(todo.getId());
        assertFalse(todo.getId().isBlank());
        assertEquals("买菜", todo.getTitle());
        assertFalse(todo.isCompleted());
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("添加多条任务各自拿到不重复的 id")
    void addAssignsDistinctIds() {
        TodoList list = new TodoList();
        Todo first = list.add("买菜");
        Todo second = list.add("做饭");
        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("按 id 删除任务")
    void removeById() {
        TodoList list = new TodoList();
        Todo todo = list.add("买菜");
        assertTrue(list.remove(todo.getId()));
        assertEquals(0, list.size());
        assertTrue(list.find(todo.getId()).isEmpty());
    }

    @Test
    @DisplayName("删除不存在的 id 返回 false 且不影响其他任务")
    void removeUnknownIdReturnsFalse() {
        TodoList list = new TodoList();
        list.add("买菜");
        assertFalse(list.remove("no-such-id"));
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("切换完成状态会翻转")
    void toggleFlipsCompletion() {
        TodoList list = new TodoList();
        Todo todo = list.add("买菜");
        assertTrue(list.toggle(todo.getId()));
        assertTrue(todo.isCompleted());
        assertTrue(list.toggle(todo.getId()));
        assertFalse(todo.isCompleted());
    }

    @Test
    @DisplayName("切换不存在的 id 返回 false")
    void toggleUnknownIdReturnsFalse() {
        TodoList list = new TodoList();
        assertFalse(list.toggle("no-such-id"));
    }

    @Test
    @DisplayName("列出全部保持加入顺序")
    void listKeepsInsertionOrder() {
        TodoList list = new TodoList();
        Todo first = list.add("买菜");
        Todo second = list.add("做饭");
        Todo third = list.add("洗碗");
        assertEquals(List.of(first, second, third), list.list());
    }

    @Test
    @DisplayName("列出全部对空列表返回空")
    void listEmptyWhenNothingAdded() {
        TodoList list = new TodoList();
        assertTrue(list.list().isEmpty());
    }
}
