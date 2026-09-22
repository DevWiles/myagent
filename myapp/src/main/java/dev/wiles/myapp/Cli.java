package dev.wiles.myapp;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * 命令行交互层。不做 System.exit，方便测试。
 */
public class Cli {

    private final TodoList list;
    private final TaskStore store;
    private final PrintStream out;

    public Cli(TodoList list, TaskStore store, PrintStream out) {
        this.list = list;
        this.store = store;
        this.out = out;
    }

    public static String usage() {
        return String.join("\n",
                "myapp - 命令行 Todo List",
                "",
                "用法:",
                "  myapp add <标题>      添加任务",
                "  myapp list            列出全部任务",
                "  myapp done <id>       标记完成/取消完成",
                "  myapp rm <id>         删除任务",
                "  myapp rename <id> <标题>  重命名任务",
                "  myapp clear           清空全部任务",
                "  myapp help            显示帮助");
    }

    /**
     * 执行一条命令，返回退出码。
     */
    public int run(String[] args) throws IOException {
        if (args.length == 0) {
            out.println(usage());
            return 0;
        }
        String cmd = args[0].toLowerCase();
        switch (cmd) {
            case "add" -> {
                if (args.length < 2) {
                    return err("缺少任务标题：myapp add <标题>");
                }
                Task t = list.add(String.join(" ", tail(args)));
                persist();
                out.println("已添加: " + t);
            }
            case "list", "ls" -> printList();
            case "done" -> {
                Long id = parseId(args);
                if (id == null) {
                    return 2;
                }
                Optional<Task> t = list.toggle(id);
                if (t.isEmpty()) {
                    return err("找不到任务 #" + id);
                }
                persist();
                out.println((t.get().done() ? "已完成: " : "取消完成: ") + t.get());
            }
            case "rm", "remove", "del" -> {
                Long id = parseId(args);
                if (id == null) {
                    return 2;
                }
                if (!list.remove(id)) {
                    return err("找不到任务 #" + id);
                }
                persist();
                out.println("已删除 #" + id);
            }
            case "rename" -> {
                if (args.length < 3) {
                    return err("用法: myapp rename <id> <新标题>");
                }
                Long id = parseId(args);
                if (id == null) {
                    return 2;
                }
                String title = String.join(" ", tail(args, 2));
                if (!list.rename(id, title)) {
                    return err("找不到任务 #" + id);
                }
                persist();
                out.println("已重命名 #" + id + " -> " + title);
            }
            case "clear" -> {
                list.clear();
                persist();
                out.println("已清空全部任务");
            }
            case "help", "-h", "--help" -> out.println(usage());
            default -> {
                out.println("未知命令: " + cmd);
                out.println(usage());
                return 2;
            }
        }
        return 0;
    }

    private void printList() {
        List<Task> tasks = list.sorted();
        if (tasks.isEmpty()) {
            out.println("暂无任务。用 'myapp add <标题>' 添加一条。");
            return;
        }
        for (Task t : tasks) {
            out.println(t);
        }
        long pending = list.pending().size();
        out.println("---");
        out.println("共 " + tasks.size() + " 条，未完成 " + pending + " 条");
    }

    private Long parseId(String[] args) {
        if (args.length < 2) {
            err("缺少任务 id");
            return null;
        }
        try {
            return Long.parseLong(args[1].trim());
        } catch (NumberFormatException e) {
            err("id 必须是数字: " + args[1]);
            return null;
        }
    }

    private String[] tail(String[] args) {
        return tail(args, 1);
    }

    private String[] tail(String[] args, int from) {
        String[] rest = new String[args.length - from];
        System.arraycopy(args, from, rest, 0, rest.length);
        return rest;
    }

    private int err(String message) {
        out.println("错误: " + message);
        return 2;
    }

    private void persist() throws IOException {
        store.save(list.all());
    }
}
