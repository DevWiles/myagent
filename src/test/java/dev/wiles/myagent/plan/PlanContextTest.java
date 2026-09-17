package dev.wiles.myagent.plan;

import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlanContextTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("从工作区采一份：目录树、工具清单、禁止命令都有")
    void collectsFromWorkspace() throws IOException {
        Files.createDirectory(tmp.resolve("src"));
        Files.writeString(tmp.resolve("pom.xml"), "x");

        PlanContext ctx = PlanContext.of(tmp, BuiltinTools.createDefault(new Workspace(tmp)));

        assertTrue(ctx.hasTree());
        assertTrue(ctx.tree().contains("pom.xml"), ctx.tree());
        assertTrue(ctx.hasTools());
        assertTrue(ctx.tools().stream().anyMatch(t -> t.name().equals("read_file")));
        assertTrue(ctx.hasForbidden());
        assertTrue(ctx.forbiddenCommands().contains("sudo"));
    }

    @Test
    @DisplayName("工具清单取自实际注册的那一份——只读模式下压根看不到写工具")
    void reflectsTheRegistryItWasGiven() {
        PlanContext ctx = PlanContext.of(tmp, BuiltinTools.createReadOnly(new Workspace(tmp)));

        assertTrue(ctx.tools().stream().anyMatch(t -> t.name().equals("read_file")));
        assertFalse(ctx.tools().stream().anyMatch(t -> t.name().equals("write_file")),
                "只读模式下不该让规划器看见写工具");
        assertFalse(ctx.tools().stream().anyMatch(t -> t.name().equals("bash")));
    }

    @Test
    @DisplayName("工具描述只取第一句：完整描述是给执行阶段看的，规划器不需要")
    void keepsOnlyTheFirstSentence() {
        PlanContext ctx = PlanContext.of(tmp, BuiltinTools.createDefault(new Workspace(tmp)));

        String bash = ctx.tools().stream()
                .filter(t -> t.name().equals("bash")).findFirst().orElseThrow().summary();

        assertTrue(bash.endsWith("。"), bash);
        assertEquals(1, bash.chars().filter(c -> c == '。').count(), "不止一句: " + bash);
    }

    @Test
    @DisplayName("none() 是合法状态：没有现场信息时规划照样能跑")
    void noneIsUsable() {
        PlanContext ctx = PlanContext.none();

        assertFalse(ctx.hasTree());
        assertFalse(ctx.hasTools());
        assertFalse(ctx.hasForbidden());
        assertNull(ctx.workspaceRoot());
    }

    @Test
    @DisplayName("禁止命令的顺序是稳定的，提示词才能进缓存、出了问题才好复现")
    void forbiddenListIsStable() {
        assertEquals(PlanContext.of(tmp, null).forbiddenCommands(),
                PlanContext.of(tmp, null).forbiddenCommands());
    }
}
