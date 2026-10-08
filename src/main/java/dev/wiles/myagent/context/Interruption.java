package dev.wiles.myagent.context;

/**
 * 上一轮断在了半路。这是「无感续做」唯一需要的状态。
 *
 * <h3>为什么只是一条记录，而不是一份「待办」</h3>
 * 真正让续做成立的东西不在这里，而在<b>历史里</b>：断掉的那一轮被
 * {@code HistoryRepair.seal} 补齐后留在上下文里，读过哪些文件、改了哪两处、
 * 哪一步没执行，全都还在。所以续做不需要「重放一份计划」，
 * 用户说一句「继续」，模型看着现场自己就接上了——和 Claude Code 一样，
 * 那句「继续」是普通提示词，不是关键词。
 *
 * <p>这条记录只回答界面的一个问题：<b>要不要提醒用户可以续</b>。
 * 不提醒的话，用户看到「调用模型失败」就以为这一轮白干了，
 * 而实际上现场是留着的——这个信息差是这个功能的全部价值。
 *
 * @param reason    人话说的原因，比如「网络错误」「已取消」「达到最大步数 25」
 * @param userInput 被打断的那一轮，用户原本说的是什么
 * @param steps     断掉之前跑到第几步
 * @param at        断掉的时刻，epoch 毫秒
 * @param messages  封口之后对话有多少条（不含 system）。断点会跟着会话存档落盘，
 *                  接回来时拿它对账：条数对不上，说明那段历史被回退过或改过，
 *                  断点指的已经是另一段现场，不认
 */
public record Interruption(String reason, String userInput, int steps, long at, int messages) {

    /** 这份断点说的是不是眼前这段对话。 */
    public boolean matches(java.util.List<?> conversation) {
        return conversation != null && conversation.size() == messages;
    }

    /**
     * 一句话交代断在哪儿，给界面拼进提示语。
     *
     * <p>不带「上一轮 / 这一轮」这种时态的词：同一条记录既要在刚断掉时打出来
     * （那时是「这一轮」），也要在下次启动时提醒（那时才是「上一轮」）。
     * 时态归说话的人管。
     *
     * <p>也<b>不带原因</b>：原因刚刚才作为错误信息打过一遍，
     * 紧接着再念一次只会把真正有用的那半句（「还能接着干」）挤到屏幕外面去。
     * 要原因的话读 {@link #reason()}。
     */
    public String describe() {
        return "断在第 " + steps + " 步";
    }

    /** 隔了这么久才接，就提醒工作区可能被动过。刚断就接的话，这句提醒只是噪音。 */
    static final long STALE_AFTER_MILLIS = 10 * 60_000L;

    /**
     * 拼在断点之后第一句话前面的那段说明。
     *
     * <h3>为什么以用户的口吻、拼在下一句前面</h3>
     * 封口补的那条「上一轮没有做完」是 assistant 说的，模型会把它当成自己确认过的结论；
     * 而「你被我打断了」本来就是用户这边的事（Codex 的 {@code <turn_aborted>}、
     * Claude Code 的 {@code [Request interrupted by user]} 都记在用户侧）。
     * 不单独插一条 user 消息，是因为有的接口不收连着两条 user——拼进下一句正好避开。
     *
     * <h3>为什么不引用上一轮的原话</h3>
     * 原任务就在上面的历史里。再念一遍，模型会把它当成新任务，从头把做完的部分再做一遍
     * ——Cline 踩过这个坑（#12975），恢复时特意不重发原任务。
     *
     * @param now 此刻，epoch 毫秒。从外面传进来，测试才拨得动钟
     */
    public String resumeNote(long now) {
        StringBuilder note = new StringBuilder("<turn_interrupted>\n")
                .append("上一轮在第 ").append(steps).append(" 步断了：")
                .append("已取消".equals(reason) ? "被我叫停" : reason).append("。现场都在上面：\n")
                .append("- 已经拿到结果的工具确实执行过，结果可信，做完的步骤不要重做；\n")
                .append("- 标着「没有执行」的调用没跑过，需要的话照原样重新发起；\n")
                .append("- 执行到一半被打断的命令或写入可能已经部分生效，先检查现状再决定是否重试。\n");
        long gap = now - at;
        if (gap >= STALE_AFTER_MILLIS) {
            note.append("距离中断已经过去约 ").append(humanGap(gap))
                    .append("，这期间文件可能被改过，动手前先核对相关文件的当前内容。\n");
        }
        return note.append("</turn_interrupted>").toString();
    }

    /** 断点会跟着存档过夜，「约 4320 分钟」没人读得懂。 */
    private static String humanGap(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " 小时" : hours / 24 + " 天";
    }
}
