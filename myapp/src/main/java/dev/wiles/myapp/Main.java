package dev.wiles.myapp;

import dev.wiles.myapp.ui.TodoFrame;

import java.io.IOException;
import java.nio.file.Path;

/**
 * myapp 入口。数据默认存到 ~/.myapp/tasks.tsv，可用 MYAPP_HOME 覆盖。
 *
 * 不带参数启动图形界面；带参数则按命令行方式执行（见 {@link Cli}）。
 */
public class Main {

    public static void main(String[] args) {
        Path home = resolveHome();
        TaskStore store = new TaskStore(home.resolve("tasks.tsv"));
        TodoList list = new TodoList();
        try {
            list.restore(store.load());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("读取数据失败: " + e.getMessage());
            System.exit(1);
        }

        if (args.length == 0) {
            launchGui(list, store);
        } else {
            runCli(list, store, args);
        }
    }

    private static void launchGui(TodoList list, TaskStore store) {
        // Swing 组件必须在事件分发线程里创建
        javax.swing.SwingUtilities.invokeLater(() -> new TodoFrame(list, store).setVisible(true));
    }

    private static void runCli(TodoList list, TaskStore store, String[] args) {
        Cli cli = new Cli(list, store, System.out);
        try {
            int code = cli.run(args);
            System.exit(code);
        } catch (IOException e) {
            System.err.println("保存数据失败: " + e.getMessage());
            System.exit(1);
        }
    }

    static Path resolveHome() {
        String env = System.getenv("MYAPP_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".myapp");
    }
}
