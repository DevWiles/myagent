package dev.wiles.myagent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.plan.Task;
import dev.wiles.myagent.plan.TaskType;
import dev.wiles.myagent.tool.safety.ApprovalPolicy;
import dev.wiles.myagent.tool.safety.ApprovalRequest;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Task read() {
        return new Task("t1", "读 Task.java", TaskType.FILE_READ, List.of());
    }

    private static Task write() {
        return new Task("t2", "改 Task.java", TaskType.FILE_WRITE, List.of("t1"));
    }

    private static List<JsonNode> read(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(line -> {
            try {
                return MAPPER.readTree(line);
            } catch (Exception e) {
                throw new AssertionError("这行不是合法 JSON: " + line, e);
            }
        }).toList();
    }

    @Test
    @DisplayName("每条记录一行 JSON，都带时间、运行号和类型")
    void writesJsonLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");
        try (AuditLog audit = new AuditLog(file)) {
            audit.planReviewed("## 计划\n改 Task.java", true, "");
            audit.taskStarted(read());
        }

        List<JsonNode> lines = read(file);
        assertEquals(2, lines.size());
        for (JsonNode line : lines) {
            assertTrue(line.hasNonNull("ts"), line.toString());
            assertTrue(line.hasNonNull("runId"), line.toString());
            assertTrue(line.hasNonNull("type"), line.toString());
        }
        assertEquals("plan_reviewed", lines.get(0).get("type").asText());
        assertEquals("task_started", lines.get(1).get("type").asText());
    }

    @Test
    @DisplayName("审计里要记下派了谁——事后查「它怎么会改到这个文件」先看这里")
    void recordsWhichRoleRanEachStep(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");
        try (AuditLog audit = new AuditLog(file)) {
            audit.taskStarted(read());
            audit.taskStarted(write());
        }

        List<JsonNode> lines = read(file);
        assertEquals("explorer", lines.get(0).get("role").asText());
        assertEquals("coder", lines.get(1).get("role").asText());
    }

    @Test
    @DisplayName("同一次运行的所有记录共享一个 runId——串不起来的日志等于没有")
    void sharesOneRunId(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");
        try (AuditLog audit = new AuditLog(file)) {
            audit.planReviewed("计划", false, "别动数据库");
            audit.taskStarted(read());
            audit.note("something", "x", 1);
        }

        List<JsonNode> lines = read(file);
        assertEquals(1, lines.stream().map(l -> l.get("runId").asText()).distinct().count());
    }

    @Test
    @DisplayName("plan 模式交上来的计划全文和用户的批复都要留：事后复盘时被批准的那份就是当时的约定")
    void planReviewCarriesPlanAndVerdict(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");
        try (AuditLog audit = new AuditLog(file)) {
            audit.planReviewed("## 计划\n1. 改 Task.java", false, "先别动接口");
            audit.planReviewed("## 计划\n1. 只改实现", true, "");
        }

        List<JsonNode> lines = read(file);
        assertFalse(lines.get(0).get("approved").asBoolean());
        assertEquals("先别动接口", lines.get(0).get("feedback").asText());
        assertTrue(lines.get(1).get("approved").asBoolean());
        assertTrue(lines.get(1).get("plan").asText().contains("只改实现"));
        assertFalse(lines.get(1).has("feedback"), "没附话就不写这个字段");
    }

    @Test
    @DisplayName("任务了结要记状态、耗时和产出——这是审计最核心的三样")
    void taskFinishedRecordsOutcome(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");
        Task task = new Task("t1", "读 Task.java", TaskType.FILE_READ, List.of());
        task.start();
        task.succeed("Java 17，用 Maven");

        try (AuditLog audit = new AuditLog(file)) {
            audit.taskFinished(task);
        }

        JsonNode line = read(file).get(0);
        assertEquals("SUCCEEDED", line.get("status").asText());
        assertEquals("Java 17，用 Maven", line.get("result").asText());
        assertTrue(line.get("durationMs").asLong() >= 0);
    }

    @Test
    @DisplayName("工具调用的参数和输出都要留底——「它到底改了什么」全靠这条")
    void recordsToolCalls(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");

        try (AuditLog audit = new AuditLog(file)) {
            audit.toolCall("t1", "write_file",
                    "{\"path\":\"a.java\"}", true, "写入 12 行");
        }

        JsonNode line = read(file).get(0);
        assertEquals("tool_call", line.get("type").asText());
        assertEquals("write_file", line.get("tool").asText());
        assertEquals("t1", line.get("taskId").asText());
        assertTrue(line.get("arguments").asText().contains("a.java"));
        assertTrue(line.get("ok").asBoolean());
    }

    @Test
    @DisplayName("审批决策要留底：批准和拒绝都是审计第一关心的事")
    void recordsApprovals(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");

        try (AuditLog audit = new AuditLog(file)) {
            audit.approval(ApprovalRequest.bash("rm -rf build", "HIGH"), ApprovalVerdict.no());
        }

        JsonNode line = read(file).get(0);
        assertEquals("approval", line.get("type").asText());
        assertEquals("rm -rf build", line.get("detail").asText());
        assertEquals("HIGH", line.get("risk").asText());
        assertFalse(line.get("approved").asBoolean());
    }

    @Test
    @DisplayName("被叫停的审批要和拒绝分开记——查账时「没人批」和「用户说不」是两回事")
    void recordsCancelledApproval(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run.jsonl");

        try (AuditLog audit = new AuditLog(file)) {
            audit.approval(ApprovalRequest.bash("rm -rf build", "HIGH"), ApprovalVerdict.interrupted());
            audit.approval(ApprovalRequest.bash("rm -rf dist", "HIGH"), ApprovalVerdict.no());
        }

        List<JsonNode> lines = read(file);
        assertFalse(lines.get(0).get("approved").asBoolean());
        assertTrue(lines.get(0).path("cancelled").asBoolean());
        assertFalse(lines.get(1).has("cancelled"), "真拒绝不该带这个字段");
    }

    @Test
    @DisplayName("写不进去也不能把 agent 拖垮——审计失败是小事，任务跑一半崩了是大事")
    void ioFailureNeverPropagates(@TempDir Path dir) {
        // 用一个「父目录是文件」的路径，保证打不开
        Path blocked = dir.resolve("not-a-dir");
        assertDoesNotThrow(() -> {
            Files.writeString(blocked, "x");
            try (AuditLog audit = new AuditLog(blocked.resolve("run.jsonl"))) {
                audit.taskStarted(new Task("t1", "读", TaskType.FILE_READ, List.of()));
                audit.note("x", "y", 1);
            }
        });
    }

    @Test
    @DisplayName("超长内容要截断，不能让一次 cat 大文件把日志撑爆")
    void clipsHugePayloads(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");

        try (AuditLog audit = new AuditLog(file)) {
            audit.toolCall("t1", "read_file", "{}", true, "x".repeat(100_000));
        }

        JsonNode line = read(file).get(0);
        assertTrue(line.get("output").asText().length() <= AuditLog.MAX_FIELD_LENGTH + 32,
                "输出没截断，长度是 " + line.get("output").asText().length());
    }

    @Test
    @DisplayName("包一层就能给任何审批策略留底，不用去每个工具里埋点")
    void auditsAnyApprovalPolicy(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("run.jsonl");

        try (AuditLog audit = new AuditLog(file)) {
            ApprovalPolicy policy = audit.audited(ApprovalPolicy.denyAll());

            assertFalse(policy.decide(ApprovalRequest.bash("rm -rf /", "删除整个目录")).approved());
        }

        JsonNode line = read(file).get(0);
        assertEquals("approval", line.get("type").asText());
        assertEquals("rm -rf /", line.get("detail").asText());
        assertFalse(line.get("approved").asBoolean());
    }

    @Test
    @DisplayName("装饰器不能改变审批结果本身")
    void decoratorKeepsTheDecision(@TempDir Path dir) {
        try (AuditLog audit = new AuditLog(dir.resolve("run.jsonl"))) {
            assertTrue(audit.audited(ApprovalPolicy.approveAll())
                    .decide(ApprovalRequest.write("写入文件", "a", "b", "")).approved());
        }
    }
}
