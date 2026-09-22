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
               "features":["plan","events_resume"],"lastSeq":42,"branch":"dev",
               "tools":[{"name":"read_file","description":"读文件"}],
               "continuable":{"id":"abc","turns":3,"messages":7,"firstMessage":"你好","savedAt":1,"count":2}}""",
        )
        val ready = event as ServerEvent.Ready
        assertEquals(1, ready.protocol)
        assertEquals(42L, ready.lastSeq)
        assertEquals(listOf("plan", "events_resume"), ready.features)
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
    fun `plan_created 的任务和分层`() {
        val event = parseServerEvent(
            """{"t":"plan_created","goal":"改配置","tasks":[
                 {"id":"t1","type":"FILE_READ","role":"explorer","roleTitle":"侦察",
                  "description":"看看","status":"PENDING","parentId":null,"dependsOn":[]}],
               "layers":[["t1"]]}""",
        ) as ServerEvent.PlanCreated
        assertEquals("t1", event.tasks.single().id)
        assertEquals(listOf(listOf("t1")), event.layers)
    }

    @Test
    fun `replay_gap 报缺口范围`() {
        val gap = parseServerEvent("""{"t":"replay_gap","from":3,"to":9}""") as ServerEvent.ReplayGap
        assertEquals(3L, gap.from)
        assertEquals(9L, gap.to)
    }
}
