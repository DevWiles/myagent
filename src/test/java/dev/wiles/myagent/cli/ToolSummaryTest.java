package dev.wiles.myagent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSummaryTest {

    @Test
    @DisplayName("单个工具用它自己的动词和量词")
    void describesSingleTool() {
        var counts = ToolSummary.newCounter();
        counts.put("read_file", 3);
        assertEquals("读取 3 个文件", ToolSummary.describe(counts));

        counts.clear();
        counts.put("bash", 1);
        assertEquals("执行 1 条命令", ToolSummary.describe(counts));
    }

    @Test
    @DisplayName("多个工具按首次调用顺序串起来")
    void keepsInsertionOrder() {
        var counts = ToolSummary.newCounter();
        counts.put("list_dir", 2);
        counts.put("read_file", 5);
        counts.put("bash", 1);

        assertEquals("列出 2 个目录 · 读取 5 个文件 · 执行 1 条命令",
                ToolSummary.describe(counts));
    }

    @Test
    @DisplayName("没登记过的工具退回通用说法，不能漏掉")
    void handlesUnknownTool() {
        var counts = ToolSummary.newCounter();
        counts.put("some_mcp_tool", 2);
        assertEquals("调用 2 次 some_mcp_tool", ToolSummary.describe(counts));
    }

    @Test
    @DisplayName("没有调用时返回空串")
    void emptyWhenNothingCalled() {
        assertEquals("", ToolSummary.describe(ToolSummary.newCounter()));
        assertEquals("", ToolSummary.describe(null));
    }

    @Test
    @DisplayName("五个内置工具都有像样的中文说法")
    void coversBuiltinTools() {
        for (String tool : new String[]{"read_file", "write_file", "list_dir",
                "bash", "create_project"}) {
            var counts = ToolSummary.newCounter();
            counts.put(tool, 1);
            String text = ToolSummary.describe(counts);
            assertFalse(text.contains(tool), tool + " 没有中文说法，直接把工具名露出来了");
        }
    }
}
