package dev.wiles.myagent.cli;

import java.nio.file.Path;

/**
 * 命令行参数。
 *
 * @param workspace     工作区目录，默认当前目录
 * @param model         覆盖配置里的模型名
 * @param planMode      只读模式：不注册写文件和执行命令的工具
 * @param unsafe        跳过工作区安全检查
 * @param color         是否输出颜色
 * @param initialPrompt 一次性执行的提示词；非空时跑完就退出，不进交互
 * @param help          打印用法
 * @param version       打印版本
 */
public record CliOptions(
        Path workspace,
        String model,
        boolean planMode,
        boolean unsafe,
        boolean color,
        String initialPrompt,
        boolean help,
        boolean version
) {
}
