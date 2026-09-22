package dev.wiles.myagent.client

import dev.wiles.myagent.client.state.ToolCall
import dev.wiles.myagent.client.state.ToolSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 摘要文案要和 `tui/src/format.ts` **逐字**一致——两块屏对同一组调用必须说同一句话。
 * 这组期望值就是 TUI 那边 summarizeTools 会说出来的原话。
 */
class ToolSummaryTest {

    private fun call(tool: String, args: String = "{}") =
        ToolCall(id = 1, tool = tool, args = args, running = false, ok = true)

    @Test
    fun `按首次出现的顺序数数`() {
        val calls = listOf(
            call("read_file"), call("read_file"), call("read_file"),
            call("bash"),
        )
        assertEquals("读取 3 个文件 · 执行 1 条命令", ToolSummary.summarize(calls))
    }

    @Test
    fun `grep 只调一次时显示搜的是什么，不显示次数`() {
        val calls = listOf(call("grep", """{"pattern": "TODO"}"""))
        assertEquals("搜索 TODO", ToolSummary.summarize(calls))
    }

    @Test
    fun `grep 调了多次退回计数`() {
        val calls = listOf(
            call("grep", """{"pattern": "a"}"""),
            call("grep", """{"pattern": "b"}"""),
        )
        assertEquals("搜索 2 次", ToolSummary.summarize(calls))
    }

    @Test
    fun `没登记的工具退回通用说法——MCP 外挂工具走这条`() {
        assertEquals("调用 1 次 mcp__fs__query", ToolSummary.summarize(listOf(call("mcp__fs__query"))))
    }

    @Test
    fun `参数不是合法 JSON 不抛，退回计数`() {
        assertEquals("搜索 1 次", ToolSummary.summarize(listOf(call("grep", "not json"))))
    }
}
