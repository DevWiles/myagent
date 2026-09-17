package dev.wiles.myagent.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanParserTest {

    /** 一份合法的五任务计划，正好压在数量下限上。 */
    private static String validJson() {
        return """
                {
                  "goal": "给 Task 加上重试能力",
                  "tasks": [
                    {"id": "t1", "type": "FILE_READ", "description": "读 Task.java 弄清状态机", "acceptance": "说清有哪几种状态", "dependsOn": []},
                    {"id": "t2", "type": "FILE_READ", "description": "读 TaskTest 看现有约定", "acceptance": "列出现有测试覆盖了什么", "dependsOn": []},
                    {"id": "t3", "type": "FILE_WRITE", "description": "加 retry 方法", "acceptance": "Task 里有 retry() 且状态迁移仍然合法", "dependsOn": ["t1", "t2"]},
                    {"id": "t4", "type": "FILE_WRITE", "description": "补测试", "acceptance": "重试的正常路径和越界路径都有用例", "dependsOn": ["t3"]},
                    {"id": "t5", "type": "VERIFICATION", "description": "跑 mvn test", "acceptance": "测试全部通过", "dependsOn": ["t4"]}
                  ]
                }
                """;
    }

    private static IllegalArgumentException parseError(String text) {
        return assertThrows(IllegalArgumentException.class, () -> PlanParser.parse(text));
    }

    // ── 正常解析 ────────────────────────────────────────────────

    @Test
    @DisplayName("标准 JSON：目标、任务、类型、依赖都解出来")
    void parsesValidPlan() {
        PlanParser.ParsedPlan parsed = PlanParser.parse(validJson());

        assertEquals("给 Task 加上重试能力", parsed.goal());
        assertEquals(5, parsed.tasks().size());

        Task t3 = parsed.tasks().get(2);
        assertEquals("t3", t3.id());
        assertEquals(TaskType.FILE_WRITE, t3.type());
        assertEquals("加 retry 方法", t3.description());
        assertEquals(List.of("t1", "t2"), t3.dependencies());
        assertEquals(0, t3.depth(), "根计划产出的都是根任务");
        assertNull(t3.parentId());
    }

    @Test
    @DisplayName("模型爱加的 ```json 围栏和前后废话，要能扒掉")
    void toleratesFencesAndChatter() {
        String messy = "好的，我把它拆成五步：\n\n```json\n" + validJson() + "\n```\n\n有问题随时说。";

        assertEquals(5, PlanParser.parse(messy).tasks().size());
    }

    @Test
    @DisplayName("类型大小写不敏感——模型写小写是常事，不值得为此打回重来")
    void typeIsCaseInsensitive() {
        String json = validJson().replace("\"FILE_READ\"", "\"file_read\"");

        assertEquals(TaskType.FILE_READ, PlanParser.parse(json).tasks().get(0).type());
    }

    @Test
    @DisplayName("dependsOn 没写就是没依赖，不该报错")
    void missingDependsOnMeansNoDependency() {
        String json = validJson().replace(", \"dependsOn\": []", "");

        assertTrue(PlanParser.parse(json).tasks().get(0).dependencies().isEmpty());
    }

    // ── 模型的各种翻车方式 ──────────────────────────────────────

    @Test
    @DisplayName("整段没有 JSON：报错要说清要的是什么，这句话是要发回给模型的")
    void rejectsTextWithoutJson() {
        IllegalArgumentException e = parseError("我觉得这个需求得先聊聊你的想法。");

        assertTrue(e.getMessage().contains("JSON"), e.getMessage());
    }

    @Test
    @DisplayName("JSON 坏了（少个括号）也不能让程序崩")
    void rejectsBrokenJson() {
        assertNotNull(parseError("{\"goal\": \"x\", \"tasks\": [").getMessage());
    }

    @Test
    @DisplayName("缺 goal：它是给用户判断「有没有理解偏」的唯一依据，不能少")
    void rejectsMissingGoal() {
        String json = validJson().replaceFirst("\"goal\": \"给 Task 加上重试能力\",", "");

        assertTrue(parseError(json).getMessage().contains("goal"));
    }

    @Test
    @DisplayName("任务太少：说明模型压根没在规划，不如直接走原来的循环")
    void rejectsTooFewTasks() {
        String json = """
                {"goal": "随便", "tasks": [
                  {"id": "t1", "type": "FILE_READ", "description": "读", "acceptance": "读到了", "dependsOn": []},
                  {"id": "t2", "type": "FILE_WRITE", "description": "写", "acceptance": "写完了", "dependsOn": ["t1"]}
                ]}
                """;

        String msg = parseError(json).getMessage();
        assertTrue(msg.contains(String.valueOf(PlanLimits.MIN_TASKS)), msg);
    }

    @Test
    @DisplayName("任务太多：模型很乐意把改错别字拆成 20 步，得拦")
    void rejectsTooManyTasks() {
        StringBuilder sb = new StringBuilder("{\"goal\": \"随便\", \"tasks\": [");
        for (int i = 1; i <= PlanLimits.MAX_TASKS + 1; i++) {
            sb.append(i > 1 ? "," : "")
                    .append("{\"id\": \"t").append(i)
                    .append("\", \"type\": \"FILE_READ\", \"description\": \"第 ").append(i)
                    .append(" 步\", \"acceptance\": \"这步做完\", \"dependsOn\": []}");
        }
        sb.append("]}");

        String msg = parseError(sb.toString()).getMessage();
        assertTrue(msg.contains(String.valueOf(PlanLimits.MAX_TASKS)), msg);
    }

    @Test
    @DisplayName("类型编错了：报错要把合法取值全列出来，模型才知道该改成什么")
    void rejectsUnknownType() {
        String json = validJson().replace("\"VERIFICATION\"", "\"RUN_TESTS\"");

        String msg = parseError(json).getMessage();
        assertTrue(msg.contains("RUN_TESTS"), msg);
        assertTrue(msg.contains("FILE_READ") && msg.contains("VERIFICATION"), msg);
    }

    @Test
    @DisplayName("任务缺 id 或描述，当场指出是第几个任务出的问题")
    void rejectsTaskMissingFields() {
        String json = validJson().replace("\"id\": \"t3\", ", "");
        assertTrue(parseError(json).getMessage().contains("id"));

        String noDesc = validJson().replace("\"description\": \"加 retry 方法\"", "\"description\": \"\"");
        assertTrue(parseError(noDesc).getMessage().contains("描述"));
    }

    @Test
    @DisplayName("acceptance 解出来了——执行阶段靠它判断能不能收口")
    void parsesAcceptance() {
        PlanParser.ParsedPlan parsed = PlanParser.parse(validJson());

        assertEquals("说清有哪几种状态", parsed.tasks().get(0).acceptance());
        assertEquals("测试全部通过", parsed.tasks().get(4).acceptance());
    }

    @Test
    @DisplayName("缺 acceptance：报错要说清它是干什么用的，这句话是要回灌给模型的")
    void rejectsMissingAcceptance() {
        String json = validJson().replace("\"acceptance\": \"测试全部通过\", ", "");

        String msg = parseError(json).getMessage();
        assertTrue(msg.contains("acceptance"), msg);
        assertTrue(msg.contains("t5"), msg);
    }

    @Test
    @DisplayName("acceptance 是空串等同于没写")
    void rejectsBlankAcceptance() {
        String json = validJson().replace("\"acceptance\": \"测试全部通过\"", "\"acceptance\": \"  \"");

        assertTrue(parseError(json).getMessage().contains("acceptance"));
    }

    @Test
    @DisplayName("占位符引用了上游，且依赖也声明了：放行")
    void acceptsDeclaredPlaceholder() {
        String json = validJson().replace("\"description\": \"加 retry 方法\"",
                "\"description\": \"按 {{t1}} 说明的状态机加 retry 方法\"");

        Task t3 = PlanParser.parse(json).tasks().get(2);
        assertTrue(t3.description().contains("{{t1}}"), t3.description());
    }

    @Test
    @DisplayName("引用了 {{t2}} 却没把 t2 写进 dependsOn：静默错误，必须当场拦")
    void rejectsUndeclaredPlaceholder() {
        String json = validJson().replace("\"description\": \"补测试\"",
                "\"description\": \"照着 {{t2}} 的约定补测试\"");

        String msg = parseError(json).getMessage();
        assertTrue(msg.contains("t2"), msg);
        assertTrue(msg.contains("dependsOn"), msg);
    }

    @Test
    @DisplayName("acceptance 里的占位符一样要声明依赖")
    void checksPlaceholdersInAcceptanceToo() {
        String json = validJson().replace("\"acceptance\": \"测试全部通过\"",
                "\"acceptance\": \"覆盖了 {{t1}} 里列出的每种状态\"");

        String msg = parseError(json).getMessage();
        assertTrue(msg.contains("t1"), msg);
    }

    @Test
    @DisplayName("引用自己：占位符只能指上游")
    void rejectsSelfPlaceholder() {
        String json = validJson().replace("\"description\": \"补测试\"",
                "\"description\": \"照着 {{t4}} 补测试\"");

        assertTrue(parseError(json).getMessage().contains("它自己"));
    }

    @Test
    @DisplayName("tasks 不是数组")
    void rejectsNonArrayTasks() {
        assertTrue(parseError("{\"goal\": \"x\", \"tasks\": \"t1, t2\"}").getMessage()
                .contains("tasks"));
    }

    // ── 子任务解析 ──────────────────────────────────────────────

    @Test
    @DisplayName("子任务挂在父任务底下：深度 1，记着爹是谁")
    void parsesSubTasks() {
        Task parent = new Task("t1", "先摸清要改哪儿", TaskType.PLANNING, List.of());
        String json = """
                {"tasks": [
                  {"id": "t1.1", "type": "FILE_WRITE", "description": "改 A", "acceptance": "A 改完了", "dependsOn": []},
                  {"id": "t1.2", "type": "VERIFICATION", "description": "跑测试", "acceptance": "测试通过", "dependsOn": ["t1.1"]}
                ]}
                """;

        List<Task> subTasks = PlanParser.parseSubTasks(json, parent);

        assertEquals(2, subTasks.size());
        assertEquals(1, subTasks.get(0).depth());
        assertEquals("t1", subTasks.get(0).parentId());
        assertEquals(List.of("t1.1"), subTasks.get(1).dependencies());
    }

    @Test
    @DisplayName("子规划不要求凑够 5 个——拆出一两步是正常的")
    void subTasksHaveNoLowerBound() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of());
        String json = """
                {"tasks": [{"id": "t1.1", "type": "FILE_WRITE", "description": "改一处", "acceptance": "改完了", "dependsOn": []}]}
                """;

        assertEquals(1, PlanParser.parseSubTasks(json, parent).size());
    }

    @Test
    @DisplayName("子任务里不许再有 PLANNING——这是「不能有孙子」那条约束的前哨")
    void rejectsNestedPlanningTask() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of());
        String json = """
                {"tasks": [{"id": "t1.1", "type": "PLANNING", "description": "再拆一层", "acceptance": "拆出来了", "dependsOn": []}]}
                """;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseSubTasks(json, parent));
        assertTrue(e.getMessage().contains("PLANNING"), e.getMessage());
    }

    @Test
    @DisplayName("一个子任务都没拆出来，也要当成错误回灌")
    void rejectsEmptySubTasks() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseSubTasks("{\"tasks\": []}", parent));
    }

    @Test
    @DisplayName("子任务超过上限：探路任务的产出该是接下来几步，不是另一份完整计划")
    void rejectsTooManySubTasks() {
        Task parent = new Task("t1", "探路", TaskType.PLANNING, List.of());
        StringBuilder sb = new StringBuilder("{\"tasks\": [");
        for (int i = 1; i <= PlanLimits.MAX_SUB_TASKS + 1; i++) {
            sb.append(i > 1 ? "," : "")
                    .append("{\"id\": \"t1.").append(i)
                    .append("\", \"type\": \"FILE_WRITE\", \"description\": \"步骤 ").append(i)
                    .append("\", \"acceptance\": \"这步做完\", \"dependsOn\": []}");
        }
        sb.append("]}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseSubTasks(sb.toString(), parent));
        assertTrue(e.getMessage().contains(String.valueOf(PlanLimits.MAX_SUB_TASKS)),
                e.getMessage());
    }
    // ── 补救任务解析 ────────────────────────────────────────────

    @Test
    @DisplayName("补救任务解出来就是一批根任务，深度 0、没有爹")
    void parsesReplan() {
        String json = """
                {"tasks": [
                  {"id": "r1", "type": "FILE_READ", "description": "查清为什么失败", "acceptance": "说清原因", "dependsOn": []},
                  {"id": "r2", "type": "FILE_WRITE", "description": "按 {{r1}} 换个做法", "acceptance": "改完了", "dependsOn": ["r1"]}
                ]}
                """;

        List<Task> tasks = PlanParser.parseReplan(json);

        assertEquals(2, tasks.size());
        assertEquals(0, tasks.get(0).depth());
        assertNull(tasks.get(0).parentId());
        assertEquals(List.of("r1"), tasks.get(1).dependencies());
    }

    @Test
    @DisplayName("空 tasks 是「这事儿做不成」，是答案不是错误")
    void emptyReplanIsAnAnswer() {
        assertTrue(PlanParser.parseReplan("{\"tasks\": []}").isEmpty());
    }

    @Test
    @DisplayName("补救任务连回原计划：那一步永远等不到就绪，必须当场拦")
    void rejectsDependencyOnTheOriginalPlan() {
        String json = """
                {"tasks": [
                  {"id": "r1", "type": "FILE_WRITE", "description": "接着改", "acceptance": "改完", "dependsOn": ["t3"]}
                ]}
                """;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseReplan(json));
        assertTrue(e.getMessage().contains("t3"), e.getMessage());
        assertTrue(e.getMessage().contains("dependsOn"), e.getMessage());
    }

    @Test
    @DisplayName("补救任务太多：那说明失败的根子在原计划思路上，不是补几步能解决的")
    void rejectsTooManyRecoveryTasks() {
        StringBuilder sb = new StringBuilder("{\"tasks\": [");
        for (int i = 1; i <= PlanLimits.MAX_REPLAN_TASKS + 1; i++) {
            sb.append(i > 1 ? "," : "")
                    .append("{\"id\": \"r").append(i)
                    .append("\", \"type\": \"FILE_READ\", \"description\": \"补 ").append(i)
                    .append("\", \"acceptance\": \"做完\", \"dependsOn\": []}");
        }
        sb.append("]}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseReplan(sb.toString()));
        assertTrue(e.getMessage().contains(String.valueOf(PlanLimits.MAX_REPLAN_TASKS)),
                e.getMessage());
    }

    @Test
    @DisplayName("补救任务同样要写 acceptance")
    void recoveryTasksNeedAcceptanceToo() {
        String json = """
                {"tasks": [{"id": "r1", "type": "FILE_READ", "description": "查", "dependsOn": []}]}
                """;

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> PlanParser.parseReplan(json)).getMessage().contains("acceptance"));
    }
}
