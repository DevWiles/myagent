package dev.wiles.myagent.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 一组待办的增删改查。
 *
 * <p>用 {@link List} 而不是 Map 存：这个类最主要的读法是「列出全部」，
 * 用户期望的也是「我加的顺序」。Map 的遍历顺序不是插入顺序（除非特意用
 * LinkedHashMap），而按 id 查找的场景远没有列出全部频繁，
 * 为后者牺牲前者的自然顺序不值当。
 *
 * <p>id 用 {@link UUID#randomUUID()} 生成，而不是内部递增计数：
 * 计数会随进程重启、多条列表合并而撞号，UUID 则不需要任何全局协调。
 */
public class TodoList {

    private final List<Todo> todos = new ArrayList<>();

    /** 新增一条待办，自动生成 id 并返回。 */
    public Todo add(String title) {
        Todo todo = new Todo(UUID.randomUUID().toString(), title);
        todos.add(todo);
        return todo;
    }

    /** 按 id 删除，返回是否真的删掉了一条。 */
    public boolean remove(String id) {
        return todos.removeIf(todo -> todo.getId().equals(id));
    }

    /** 翻转完成状态，返回是否命中。 */
    public boolean toggle(String id) {
        return find(id).map(todo -> {
            todo.setCompleted(!todo.isCompleted());
            return true;
        }).orElse(false);
    }

    /** 显式设置完成状态，返回是否命中。 */
    public boolean setCompleted(String id, boolean completed) {
        return find(id).map(todo -> {
            todo.setCompleted(completed);
            return true;
        }).orElse(false);
    }

    /** 按 id 查。 */
    public Optional<Todo> find(String id) {
        return todos.stream().filter(todo -> todo.getId().equals(id)).findFirst();
    }

    /** 列出全部，按加入顺序。 */
    public List<Todo> list() {
        return List.copyOf(todos);
    }

    /** 只列未完成的。 */
    public List<Todo> listPending() {
        return todos.stream().filter(todo -> !todo.isCompleted()).toList();
    }

    public int size() {
        return todos.size();
    }
}
