package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 审批答复的三种结局：批、拒、没答（被叫停）。 */
class ApprovalVerdictTest {

    @Test
    @DisplayName("取消不是拒绝：给模型的那句话里不能出现「用户拒绝」")
    void cancelledIsNotRefusal() {
        ApprovalVerdict verdict = ApprovalVerdict.interrupted();

        assertFalse(verdict.approved(), "没批就不能执行");
        assertTrue(verdict.cancelled());
        String message = verdict.deniedMessage("用户拒绝执行这条命令。请换一种做法。");
        assertFalse(message.contains("拒绝"),
                "用户只是按了停，没表过态。说成拒绝，他回头说「继续」时模型会绕开这一步: " + message);
        assertFalse(message.contains("换一种做法"),
                "工具那句「换个做法」是写给真拒绝的，取消时照搬就是在误导: " + message);
        assertTrue(message.contains("已取消") && message.contains("没有执行"),
                "要和封口补的那句口径一致，模型才认得出是同一件事: " + message);
    }

    @Test
    @DisplayName("真拒绝不受影响：工具那句话和用户补的话原样保留")
    void refusalUnchanged() {
        ApprovalVerdict verdict = ApprovalVerdict.no("用户补充: 别用 curl");

        assertFalse(verdict.cancelled());
        assertEquals("用户拒绝。 用户补充: 别用 curl", verdict.deniedMessage("用户拒绝。"));
    }
}
