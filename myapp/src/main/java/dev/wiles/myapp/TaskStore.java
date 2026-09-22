package dev.wiles.myapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 简易文件存储：每行一条任务，字段用 \t 分隔。
 * 格式：id \t done(0/1) \t createdAt(ISO) \t title
 */
public class TaskStore {

    private final Path file;

    public TaskStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public List<Task> load() throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<Task> tasks = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            tasks.add(parse(line));
        }
        return tasks;
    }

    public void save(List<Task> tasks) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (Task t : tasks) {
            lines.add(format(t));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private String format(Task t) {
        return t.id() + "\t" + (t.done() ? 1 : 0) + "\t" + t.createdAt() + "\t" + t.title();
    }

    private Task parse(String line) {
        String[] parts = line.split("\t", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("数据行格式错误: " + line);
        }
        long id = Long.parseLong(parts[0].trim());
        boolean done = "1".equals(parts[1].trim());
        LocalDateTime createdAt = LocalDateTime.parse(parts[2].trim());
        return new Task(id, parts[3], done, createdAt);
    }
}
