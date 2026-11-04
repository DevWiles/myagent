package dev.wiles.myagent.client

import dev.wiles.myagent.client.protocol.ServerEvent
import dev.wiles.myagent.client.protocol.parseServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报文样例全部抄自 docs/protocol.md——测试对着文档，不对着后端实现。 */
class ProtocolParseTest {

    @Test
    fun `ready 帧全字段`() {
        val event = parseServerEvent(
            """{"t":"ready","seq":0,"protocol":1,"version":"0.3.0","workspace":"/tmp/x",
               "model":"deepseek-chat","planMode":false,"hasApiKey":true,"busy":false,
               "features":["plan_mode","events_resume"],"lastSeq":42,"branch":"dev","mode":"normal",
               "tools":[{"name":"read_file","description":"读文件"}],
               "continuable":{"id":"abc","turns":3,"messages":7,"firstMessage":"你好","savedAt":1,"count":2}}""",
        )
        val ready = event as ServerEvent.Ready
        assertEquals(1, ready.protocol)
        assertEquals(42L, ready.lastSeq)
        assertEquals(listOf("plan_mode", "events_resume"), ready.features)
        assertEquals("normal", ready.mode)
        assertTrue(ready.skills.isEmpty())
        assertEquals("read_file", ready.tools.single().name)
        assertEquals(2, ready.continuable?.count)
    }

    @Test
    fun `广播事件带 seq，定向回执没有`() {
        val delta = parseServerEvent("""{"t":"text_delta","seq":7,"text":"你"}""")
        assertEquals(7L, (delta as ServerEvent.TextDelta).seq)
        val err = parseServerEvent("""{"t":"error","message":"x"}""")
        assertNull((err as ServerEvent.ErrorEvent).seq)
    }

    @Test
    fun `approval_request 老后端缺的字段全给默认值`() {
        val event = parseServerEvent(
            """{"t":"approval_request","id":3,"action":"执行命令","detail":"rm -rf target","reason":"会删文件"}""",
        )
        val approval = event as ServerEvent.ApprovalRequest
        // kind 缺省当 bash；suggestions 缺省空数组 = 这次记不成规则（同 TUI）
        assertEquals("bash", approval.kind)
        assertTrue(approval.suggestions.isEmpty())
        assertEquals("", approval.preview)
    }

    @Test
    fun `tool_finished 的 diff 是可选的`() {
        val bare = parseServerEvent(
            """{"t":"tool_finished","id":1,"tool":"bash","ok":true,"output":"done"}""",
        ) as ServerEvent.ToolFinished
        assertNull(bare.diff)
        val withDiff = parseServerEvent(
            """{"t":"tool_finished","id":2,"tool":"write_file","ok":true,"output":"","diff":"+x"}""",
        ) as ServerEvent.ToolFinished
        assertEquals("+x", withDiff.diff)
    }

    @Test
    fun `不认识的 t 静默忽略，脏报文不抛异常`() {
        assertNull(parseServerEvent("""{"t":"hologram","x":1}"""))
        assertNull(parseServerEvent("not json"))
        assertNull(parseServerEvent("""[1,2,3]"""))
    }

    @Test
    fun `plan_confirm_request 两种 kind，老后端缺 kind 当 exit`() {
        val enter = parseServerEvent("""{"t":"plan_confirm_request","id":1,"kind":"enter"}""")
            as ServerEvent.PlanConfirmRequest
        assertEquals("enter", enter.kind)
        assertEquals("", enter.plan)
        val exit = parseServerEvent("""{"t":"plan_confirm_request","id":2,"plan":"## 计划"}""")
            as ServerEvent.PlanConfirmRequest
        assertEquals("exit", exit.kind)
        assertEquals("## 计划", exit.plan)
    }

    @Test
    fun `mode 和 hook`() {
        val mode = parseServerEvent("""{"t":"mode","seq":5,"mode":"plan"}""") as ServerEvent.ModeChanged
        assertEquals("plan", mode.mode)
        val hook = parseServerEvent("""{"t":"hook","blocked":true,"message":"不行"}""") as ServerEvent.Hook
        assertTrue(hook.blocked)
        assertEquals("不行", hook.message)
    }

    @Test
    fun `已经下线的 DAG 计划事件静默忽略`() {
        assertNull(parseServerEvent("""{"t":"plan_created","goal":"g","tasks":[],"layers":[]}"""))
        assertNull(parseServerEvent("""{"t":"plan_finished","status":"COMPLETED"}"""))
    }

    @Test
    fun `replay_gap 报缺口范围`() {
        val gap = parseServerEvent("""{"t":"replay_gap","from":3,"to":9}""") as ServerEvent.ReplayGap
        assertEquals(3L, gap.from)
        assertEquals(9L, gap.to)
    }
}
