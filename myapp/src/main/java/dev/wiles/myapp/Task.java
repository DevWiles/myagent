package dev.wiles.myapp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 一条待办事项。
 */
public class Task {

    private final long id;
    private String title;
    private boolean done;
    private final LocalDateTime createdAt;

    public Task(long id, String title) {
        this(id, title, false, LocalDateTime.now());
    }

    public Task(long id, String title, boolean done, LocalDateTime createdAt) {
        this.id = id;
        this.title = Objects.requireNonNull(title, "title").trim();
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        this.done = done;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public long id() {
        return id;
    }

    public String title() {
        return title;
    }

    public boolean done() {
        return done;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public void setTitle(String title) {
        String trimmed = Objects.requireNonNull(title, "title").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        this.title = trimmed;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    @Override
    public String toString() {
        return "[" + (done ? "x" : " ") + "] #" + id + " " + title;
    }
}
