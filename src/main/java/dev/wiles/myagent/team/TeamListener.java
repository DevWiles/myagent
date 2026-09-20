package dev.wiles.myagent.team;

import dev.wiles.myagent.agent.AgentEvent;
import dev.wiles.myagent.plan.Task;

/**
 * 一场圆桌的全部动静。两个界面靠它把过程画出来。
 *
 * <p>和 {@code PlanListener} 一样全部给空实现：只关心其中两件事的调用方，
 * 不该被迫写一堆空方法；往里加新事件也不会把已有实现弄崩。
 *
 * <p>方法顺序就是事情发生的顺序。
 */
public interface TeamListener {

    /** 人挑好了，还没开始。界面该把名单和理由亮出来——这是花钱之前最后一眼。 */
    default void onCastChosen(String goal, Cast cast) {
    }

    default void onSeatStarted(Task seat) {
    }

    /** 一席说完了。<b>哑火的那几席也走这里</b>，它们同样是「这一席有结果了」。 */
    default void onSeatFinished(Task seat) {
    }

    /** 开始合议。单独一个事件，因为界面到这儿要换个画法：前面是并列的几席，这里是收口。 */
    default void onSynthesisStarted(Task synthesis) {
    }

    /** 席位里 ReAct 子循环的动静，带着是哪一席一起给。 */
    default void onAgentEvent(Task seat, AgentEvent event) {
    }

    default void onFinished(TeamResult result) {
    }

    static TeamListener none() {
        return new TeamListener() {
        };
    }
}
