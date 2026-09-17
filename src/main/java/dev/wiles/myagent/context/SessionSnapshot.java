package dev.wiles.myagent.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.wiles.myagent.llm.ChatMessage;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次会话的存档。关掉终端再打开，对话还能接着说。
 *
 * <h3>为什么<b>不</b>存 system 消息</h3>
 * 这是整个会话恢复里最容易埋雷的地方，所以用结构挡掉，而不是靠记性。
 *
 * <p>system 消息是每轮请求的第 0 条，里面有工作区路径，
 * <b>以及长期记忆的索引</b>。存档里如果带着它，恢复时照搬，就意味着：
 * 上次会话之后新记的所有东西，在恢复出来的会话里<b>永远看不见</b>。
 * 而这个 bug 的现象是「明明 remember 成功了，下次它却不知道」——
 * 记忆写在盘上，日志也显示写成功了，就是不生效，极难查。
 *
 * <p>所以这里干脆<b>存不下</b> system：{@link #messages} 从第 1 条起。
 * 恢复时由 {@code Agent.reset()} 现场生成一条新的，
 * 带上此刻最新的记忆索引。想犯这个错都没有机会。
 *
 * <h3>为什么要记 workspace 和 model</h3>
 * 换了目录不给恢复——一段对话里全是针对某个仓库的上下文，
 * 拿到别的项目里接着说，模型会拿 A 项目的结论去改 B 项目。
 * model 只用来在提示语里告诉用户「上次是用哪个模型聊的」，不做拦截。
 *
 * @param format   存档格式版本。对不上就当没有——宁可重来，也不能按旧结构解析出错误的历史
 * @param workspace 存档属于哪个工作区，绝对路径
 * @param model    上次用的模型
 * @param savedAt  存的时刻，epoch 毫秒
 * @param messages 对话内容，<b>不含 system</b>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSnapshot(
        int format,
        String workspace,
        String model,
        long savedAt,
        List<ChatMessage> messages) {

    /** 当前格式版本。改了消息结构就把它 +1，老存档会被自动忽略。 */
    public static final int FORMAT = 1;

    public SessionSnapshot {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    /**
     * 从一段活着的历史造一份存档。
     *
     * @param history 完整历史，<b>包含</b> index 0 的 system；这里会把它摘掉
     */
    public static SessionSnapshot of(List<ChatMessage> history, Path workspace,
                                     String model, long now) {
        List<ChatMessage> conversation = history == null || history.size() <= 1
                ? List.of()
                : history.subList(1, history.size());
        return new SessionSnapshot(FORMAT,
                workspace == null ? null : workspace.toAbsolutePath().normalize().toString(),
                model, now, conversation);
    }

    /** 格式对得上、而且确实有内容可恢复。 */
    @JsonIgnore
    public boolean isRestorable() {
        return format == FORMAT && !messages.isEmpty();
    }

    /** 是这个工作区的吗。哈希可能撞，用户也可能手工拷过存档文件，所以要再验一道。 */
    public boolean belongsTo(Path candidate) {
        if (workspace == null || candidate == null) {
            return false;
        }
        return workspace.equals(candidate.toAbsolutePath().normalize().toString());
    }

    /**
     * 修补过的、可以直接用的对话。
     *
     * <p>存档可能停在「工具点了、结果没回来」的当口（{@code kill -9} 的典型现场），
     * 那种残缺会让之后<b>每一次</b>请求都 400，见 {@link HistoryRepair}。
     */
    @JsonIgnore
    public List<ChatMessage> restorable() {
        return HistoryRepair.repair(messages);
    }

    /** 恢复之后能接着聊的对话轮数——给界面显示「上次聊了 N 轮」用。 */
    @JsonIgnore
    public int turns() {
        return (int) restorable().stream()
                .filter(m -> ChatMessage.ROLE_USER.equals(m.role()))
                .count();
    }

    /** 上次用户说的第一句话，用来在提示语里告诉他「上次在聊什么」。 */
    @JsonIgnore
    public String firstUserMessage() {
        return restorable().stream()
                .filter(m -> ChatMessage.ROLE_USER.equals(m.role()))
                .map(ChatMessage::content)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("");
    }
}
