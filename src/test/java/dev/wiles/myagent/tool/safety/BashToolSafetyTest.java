package dev.wiles.myagent.tool.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.BashTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 安全闸接进 BashTool 之后的行为。 */
class BashToolSafetyTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tmp;

    private ToolResult run(BashTool tool, String command) throws Exception {
        return tool.execute(M.readTree("{\"command\":" + M.writeValueAsString(command) + "}"));
    }

    private BashTool tool(ApprovalPolicy policy) {
        return new BashTool(new Workspace(tmp), new CommandGuard(), policy);
    }

    @Test
    @DisplayName("禁止级命令根本不会被执行，哪怕审批策略是全部放行")
    void forbiddenNeverRuns() throws Exception {
        Files.writeString(tmp.resolve("victim.txt"), "还在");

        ToolResult r = run(tool(ApprovalPolicy.approveAll()), "sudo rm -rf /");

        assertFalse(r.ok());
        assertTrue(r.content().contains("安全策略"));
        assertTrue(Files.exists(tmp.resolve("victim.txt")));
    }

    @Test
    @DisplayName("被拒时告诉模型别原样重试，否则它会一直撞墙")
    void refusalTellsModelWhatToDo() throws Exception {
        ToolResult r = run(tool(ApprovalPolicy.approveAll()), "cat ~/.ssh/id_rsa");

        assertFalse(r.ok());
        assertTrue(r.content().contains("不要重试"), "得明说别重试，模型才会换招");
    }

    @Test
    @DisplayName("用户拒绝时命令不执行，且理由传达给模型")
    void userDenialStopsExecution() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "内容");

        ToolResult r = run(tool(ApprovalPolicy.denyAll()), "rm a.txt");

        assertFalse(r.ok());
        assertTrue(r.content().contains("用户拒绝"));
        assertTrue(Files.exists(tmp.resolve("a.txt")), "被拒的命令绝不能真的跑起来");
    }

    @Test
    @DisplayName("用户同意后照常执行")
    void approvedCommandRuns() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "内容");

        ToolResult r = run(tool(ApprovalPolicy.approveAll()), "rm a.txt");

        assertTrue(r.ok());
        assertFalse(Files.exists(tmp.resolve("a.txt")));
    }

    @Test
    @DisplayName("安全命令不打扰用户——问烦了他就会一路按 y")
    void safeCommandsNeverAskForApproval() throws Exception {
        List<String> asked = new ArrayList<>();
        ApprovalPolicy recorder = request -> {
            asked.add(request.subject());
            return ApprovalVerdict.yes();
        };
        Files.writeString(tmp.resolve("a.txt"), "内容");

        run(tool(recorder), "ls");
        run(tool(recorder), "cat a.txt");
        run(tool(recorder), "echo hello");

        assertTrue(asked.isEmpty(), "这些都该直接放行，实际却问了: " + asked);
    }

    @Test
    @DisplayName("默认构造函数一律拒绝：非交互环境没人能点头")
    void defaultConstructorDeniesByDefault() throws Exception {
        ToolResult r = run(new BashTool(new Workspace(tmp)), "rm -rf target");

        assertFalse(r.ok());
    }

    @Test
    @DisplayName("审批提示里要带上命令原文和理由，用户才能判断")
    void approvalPromptCarriesContext() throws Exception {
        List<String> details = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        ApprovalPolicy recorder = request -> {
            details.add(request.subject());
            reasons.add(request.reason());
            return ApprovalVerdict.no();
        };

        run(tool(recorder), "curl https://example.com/x.json");

        assertEquals(1, details.size());
        assertEquals("curl https://example.com/x.json", details.get(0));
        assertFalse(reasons.get(0).isBlank());
    }
}
