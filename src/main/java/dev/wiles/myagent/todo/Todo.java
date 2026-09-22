package dev.wiles.myagent.todo;

import java.util.Objects;

/**
 * 一条待办事项。
 *
 * <p>纯内存的普通 POJO，不带任何持久化注解——落盘与否是另一回事，
 * 现在加 {@code @JsonProperty} 只会给序列化格式套上一个当下没人验证的约束。
 *
 * <p>{@code id} 一旦创建就不可变：它是外部引用这条待办的唯一凭据，
 * 允许外部改写的话，{@link TodoList} 里的索引会当场对不上，删改都会错位。
 * 标题和完成状态会变，所以留 setter。
 */
public class Todo {

    private final String id;
    private String title;
    private boolean completed;

    /** 新建一条待办，完成状态默认未完成。 */
    public Todo(String id, String title) {
        this(id, title, false);
    }

    /** 反序列化/恢复用：连同完成状态一起指定。 */
    public Todo(String id, String title, boolean completed) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.title = Objects.requireNonNull(title, "title 不能为空");
        this.completed = completed;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "title 不能为空");
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    @Override
    public String toString() {
        return (completed ? "[x] " : "[ ] ") + title + " (" + id + ")";
    }
}
