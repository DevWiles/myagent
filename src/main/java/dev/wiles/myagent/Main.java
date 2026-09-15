package dev.wiles.myagent;

import dev.wiles.myagent.cli.Cli;
import dev.wiles.myagent.cli.CliOptions;

/** 程序入口：解析参数，然后交给 Repl。 */
public final class Main {

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = Cli.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(Cli.usage());
            return;
        }
        if (options.version()) {
            System.out.println("myagent " + Cli.VERSION);
            return;
        }
        new Repl(options).run();
    }
}
