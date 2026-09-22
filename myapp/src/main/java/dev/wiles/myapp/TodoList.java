package dev.wiles.myapp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 待办清单：负责增删改查与持久化协调。
 */
public class TodoList {

    private final List<Task> tasks = new ArrayList<>();
    private long nextId = 1;

    public Task add(String title) {
        Task task = new Task(nextId++, title);
        tasks.add(task);
        return task;
    }

    public boolean remove(long id) {
        return tasks.removeIf(t -> t.id() == id);
    }

    public Optional<Task> find(long id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst();
    }

    /**
     * 切换完成状态，返回切换后的任务；找不到返回空。
     */
    public Optional<Task> toggle(long id) {
        return find(id).map(t -> {
            t.setDone(!t.done());
            return t;
        });
    }

    public boolean rename(long id, String title) {
        Optional<Task> task = find(id);
        task.ifPresent(t -> t.setTitle(title));
        return task.isPresent();
    }

    public List<Task> all() {
        return List.copyOf(tasks);
    }

    /** 未完成在前、按 id 升序。 */
    public List<Task> sorted() {
        return tasks.stream()
                .sorted(Comparator.comparing(Task::done).thenComparingLong(Task::id))
                .toList();
    }

    public List<Task> pending() {
        return tasks.stream().filter(t -> !t.done()).toList();
    }

    public int size() {
        return tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public void clear() {
        tasks.clear();
        nextId = 1;
    }

    /** 供持久化恢复使用。 */
    void restore(List<Task> loaded) {
        tasks.clear();
        tasks.addAll(loaded);
        nextId = tasks.stream().mapToLong(Task::id).max().orElse(0) + 1;
    }
}
