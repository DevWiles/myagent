package dev.wiles.myagent;

/**
 * 程序入口。
 * 目前只负责启动一个交互式 REPL；后续 agent 循环会挂在 Repl 后面。
 */
public final class Main {

    public static void main(String[] args) {
        new Repl().run();
    }
}
