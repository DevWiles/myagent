package dev.wiles.myagent.cli;

import java.nio.file.Path;

/**
 * 命令行参数。
 *
 * <p>这里<b>只剩无头模式用得上的那几个</b>。颜色、转圈、鼠标、全屏、一次性提示词
 * 那批是自带终端界面的旋钮，界面删掉之后它们一个都不再有任何效果——
 * 留着比删掉危险：用户敲了 {@code --no-color} 以为关掉了什么，其实什么也没发生。
 * <b>不起作用的开关比没有这个开关更糟</b>，它骗人。
 *
 * @param workspace 工作区目录，默认当前目录
 * @param model     覆盖配置里的模型名
 * @param readOnly  只读模式：不注册写文件和执行命令的工具
 * @param unsafe    跳过工作区安全检查
 * @param help      打印用法
 * @param version   打印版本
 * @param serve     无头模式。<b>现在是唯一的运行方式</b>，留着这个开关是因为
 *                  「显式说明自己要什么」比「什么都不写就默认起服务」安全——
 *                  手滑敲一下 {@code java -jar myagent.jar} 不该在你的家目录上起一个服务
 * @param port      监听端口。0 表示让系统随便挑一个（推荐，端口号会打在 stdout）
 * @param lan       局域网模式：绑 0.0.0.0 让手机等远程客户端能连进来。
 *                  默认关——「只听 127.0.0.1」是安全边界，跨过去必须显式说
 *                  （见 docs/protocol.md 1.1 节）
 */
public record CliOptions(
        Path workspace,
        String model,
        boolean readOnly,
        boolean unsafe,
        boolean help,
        boolean version,
        boolean serve,
        int port,
        boolean lan
) {
}
