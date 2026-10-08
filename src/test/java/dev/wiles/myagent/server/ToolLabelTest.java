package dev.wiles.myagent.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 工具级检查点的标签：清单里要一眼认得出是哪一步。 */
class ToolLabelTest {

    @Test
    @DisplayName("取路径或命令；create_project 列出它的文件；参数坏了也不抛")
    void labelsTheStep() {
        assertEquals("write_file src/a.py",
                AgentServer.toolLabel("write_file", "{\"path\":\"src/a.py\",\"content\":\"x\"}"));
        assertEquals("bash mvn -q test", AgentServer.toolLabel("bash", "{\"command\":\"mvn -q test\"}"));
        assertEquals("create_project a.py, b.py", AgentServer.toolLabel("create_project",
                "{\"files\":[{\"path\":\"a.py\"},{\"path\":\"b.py\"}]}"));
        assertDoesNotThrow(() -> AgentServer.toolLabel("mcp__x__y", "不是 JSON"));
        assertTrue(AgentServer.toolLabel("bash", "{\"command\":\"" + "x".repeat(200) + "\"}").length() <= 61);
    }
}
