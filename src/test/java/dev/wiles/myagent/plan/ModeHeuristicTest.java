package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启发式的行为必须被例句钉死。
 *
 * <p>这种判断最容易在「顺手加个关键词」之后悄悄漂移：某天往范围词里加了个「一遍」，
 * 「再看一遍 pom.xml」就从 DIRECT 变成 ASK 了，而这种退化不会有任何报错，
 * 只会让用户觉得「它最近怎么老问我要不要拆计划」。
 */
class ModeHeuristicTest {

    private static ExecutionMode mode(String request) {
        return ModeHeuristic.modeOf(request);
    }

    // ── 直接干 ──────────────────────────────────────────────────

    @Test
    @DisplayName("查询就是查询，别为它多花一次规划调用")
    void questionsGoDirect() {
        assertEquals(ExecutionMode.DIRECT, mode("PlanParser 为什么要从第一个 { 抠到最后一个 }"));
        assertEquals(ExecutionMode.DIRECT, mode("这个项目用的是 Java 几？"));
        assertEquals(ExecutionMode.DIRECT, mode("TaskGraph 里那个三色染色是怎么查环的"));
        assertEquals(ExecutionMode.DIRECT, mode("有没有现成的重试工具类"));
    }

    @Test
    @DisplayName("只读动词：看看、读一下、列出")
    void readOnlyVerbsGoDirect() {
        assertEquals(ExecutionMode.DIRECT, mode("看看 PlanExecutor 是怎么取消的"));
        assertEquals(ExecutionMode.DIRECT, mode("读一下 pom.xml"));
        assertEquals(ExecutionMode.DIRECT, mode("解释一下回灌重试那段"));
    }

    @Test
    @DisplayName("范围词撞上只读动词，互相抵消——「列出所有类」还是查询")
    void scopeCancelledByReadingIntent() {
        assertEquals(ExecutionMode.DIRECT, mode("列出 plan 包下所有的类"));
    }

    @Test
    @DisplayName("小改动不值得走规划")
    void smallEditsGoDirect() {
        assertEquals(ExecutionMode.DIRECT, mode("改个错别字"));
        assertEquals(ExecutionMode.DIRECT, mode("给 Task 加个 toString"));
    }

    // ── 先问一句 ────────────────────────────────────────────────

    @Test
    @DisplayName("有第二步，但不算大活：问一句最划算")
    void twoStepsAsk() {
        assertEquals(ExecutionMode.ASK, mode("给 Task 加上重试能力，然后跑测试确认没弄坏"));
    }

    @Test
    @DisplayName("单个重活动词：够可疑，但还不到直接开规划")
    void singleHeavyVerbAsks() {
        assertEquals(ExecutionMode.ASK, mode("把 llm 包迁移到 OkHttp 5"));
    }

    // ── 直接进规划 ──────────────────────────────────────────────

    @Test
    @DisplayName("重活 + 大范围：明摆着是好几步")
    void heavyPlusScopePlans() {
        assertEquals(ExecutionMode.PLAN, mode("重构整个项目的错误处理"));
    }

    @Test
    @DisplayName("范围 + 多步")
    void scopePlusMultiStepPlans() {
        assertEquals(ExecutionMode.PLAN,
                mode("把所有 FILE_WRITE 任务改成先读后写，并且补上测试"));
    }

    @Test
    @DisplayName("肯另起一行编号，本身就是在说「这是好几件事」")
    void enumerationPlans() {
        assertEquals(ExecutionMode.PLAN, mode("""
                帮我做这几件事：
                1. 给 Task 加重试字段
                2. 调度器里接上重试
                3. 跑一遍测试"""));
        assertEquals(ExecutionMode.PLAN, mode("""
                - 改 A
                - 改 B
                - 验证"""));
    }

    // ── 长度这条坑 ──────────────────────────────────────────────

    @Test
    @DisplayName("短不等于简单：七个字的「重构整个项目」照样要规划")
    void shortCanStillBeHuge() {
        assertEquals(ExecutionMode.PLAN, mode("重构整个项目"));
    }

    @Test
    @DisplayName("长不等于复杂：四十多个字的查询还是查询")
    void longCanStillBeTrivial() {
        assertEquals(ExecutionMode.DIRECT, mode(
                "帮我看一下 src/main/java/dev/wiles/myagent/plan/PlanExecutor.java "
                        + "里 settle 方法的取消分支，是怎么判定该报 CANCELLED 还是 FAILED 的"));
    }

    @Test
    @DisplayName("长度只在已经有别的信号时才加权，自己不定档")
    void lengthOnlyWeighs() {
        String padding = "另外这件事挺要紧的请仔细一点不要漏掉任何细节谢谢啦真的拜托了".repeat(3);

        // 一句没有任何信号的长句，不该因为长就被推去规划
        assertEquals(ExecutionMode.DIRECT, mode(padding));
    }

    // ── 理由与边界 ──────────────────────────────────────────────

    @Test
    @DisplayName("判断要能说出理由——自动进规划却不说为什么，用户只会觉得它突然变啰嗦")
    void explainsItself() {
        ModeHeuristic.Decision decision = ModeHeuristic.assess("重构整个项目的错误处理");

        assertEquals(ExecutionMode.PLAN, decision.mode());
        assertFalse(decision.reasons().isEmpty());
        assertTrue(decision.explain().contains("改动面"), decision.explain());
        assertTrue(decision.explain().contains("范围"), decision.explain());
    }

    @Test
    @DisplayName("同一个词说三遍，不比说一遍更能说明事情复杂")
    void repeatedKeywordDoesNotStack() {
        int once = ModeHeuristic.assess("先改 A 然后改 B").score();
        int thrice = ModeHeuristic.assess("先改 A 然后改 B 然后改 C 然后改 D").score();

        assertEquals(once, thrice);
    }

    @Test
    @DisplayName("单个类别有封顶，堆关键词堆不出高分")
    void categoryScoreIsCapped() {
        int score = ModeHeuristic.assess("所有全部每个批量逐个挨个统统都要改").score();

        assertTrue(score <= ModeHeuristic.PLAN_THRESHOLD + 2, "封顶没生效，分数=" + score);
    }

    @Test
    @DisplayName("空输入不炸，也不平白无故去规划")
    void emptyIsDirect() {
        assertEquals(ExecutionMode.DIRECT, mode(null));
        assertEquals(ExecutionMode.DIRECT, mode(""));
        assertEquals(ExecutionMode.DIRECT, mode("   "));
        assertTrue(ModeHeuristic.assess(null).reasons().isEmpty());
    }

    @Test
    @DisplayName("needsPlan 只对 PLAN 为真——ASK 还没定，不能当成要规划")
    void needsPlanIsStrict() {
        assertTrue(ExecutionMode.PLAN.needsPlan());
        assertFalse(ExecutionMode.ASK.needsPlan());
        assertFalse(ExecutionMode.DIRECT.needsPlan());
    }
}
