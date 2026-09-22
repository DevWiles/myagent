package dev.wiles.myagent.client

import dev.wiles.myagent.client.protocol.parseServerEvent
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * reducer 的行为要和 tui/src/store.ts 一致——同一串事件在两块屏上长同一份转录。
 * 用例覆盖的正是当初 TUI 里踩过坑的那些语义。
 */
class SessionStoreTest {

    private fun store() = SessionStore()

    private fun SessionStore.feed(vararg lines: String) {
        for (line in lines) apply(parseServerEvent(line)!!)
    }

    @Test
    fun `text_delta 逐段拼进同一条 assistant`() {
        val s = store()
        s.feed(
            """{"t":"text_delta","text":"你"}""",
            """{"t":"text_delta","text":"好"}""",
        )
        val entry = s.state.value.entries.single() as Entry.Assistant
        assertEquals("你好", entry.text)
    }

    @Test
    fun `thought 已经从流式打过就不再打第二遍`() {
        val s = store()
        s.feed(
            """{"t":"text_delta","text":"我先看看 README"}""",
            """{"t":"thought","text":"我先看看 README"}""",
        )
        assertEquals(1, s.state.value.entries.size)
    }

    @Test
    fun `连着调用的工具归同一组，结果按 id 回填`() {
        val s = store()
        s.feed(
            """{"t":"tool_started","id":1,"tool":"read_file","arguments":"a.txt"}""",
            """{"t":"tool_started","id":2,"tool":"read_file","arguments":"b.txt"}""",
            """{"t":"tool_finished","id":2,"tool":"read_file","ok":true,"output":"B"}""",
            """{"t":"tool_finished","id":1,"tool":"read_file","ok":false,"output":"没这文件"}""",
        )
        val tools = s.state.value.entries.single() as Entry.Tools
        assertEquals(2, tools.calls.size)
        // 并行时开始和结束交错到达，按 id 配对而不是「最近那个」
        assertEquals(false, tools.calls.first { it.id == 1 }.ok)
        assertEquals(true, tools.calls.first { it.id == 2 }.ok)
    }

    @Test
    fun `done 只在流式没走通时兜底`() {
        val streamed = store()
        streamed.feed(
            """{"t":"text_delta","text":"答案"}""",
            """{"t":"done","text":"答案","steps":1}""",
        )
        assertEquals(1, streamed.state.value.entries.size)

        val silent = store()
        silent.feed("""{"t":"done","text":"答案","steps":1}""")
        assertEquals("答案", (silent.state.value.entries.single() as Entry.Assistant).text)
    }

    @Test
    fun `busy false 清掉审批框和计划确认`() {
        val s = store()
        s.feed(
            """{"t":"approval_request","id":1,"action":"x","detail":"y","reason":"z"}""",
            """{"t":"busy","busy":false}""",
        )
        assertNull(s.state.value.approval)
    }

    @Test
    fun `压缩进度条原地更新，done 时整条移走`() {
        val s = store()
        s.feed(
            """{"t":"compacting","phase":"map","done":1,"total":3,"percent":20}""",
            """{"t":"compacting","phase":"map","done":2,"total":3,"percent":60}""",
        )
        assertEquals(1, s.state.value.entries.size)
        assertEquals(60, (s.state.value.entries.single() as Entry.Compacting).percent)
        s.feed("""{"t":"compacting","phase":"done","done":3,"total":3,"percent":100}""")
        assertTrue(s.state.value.entries.isEmpty())
    }

    @Test
    fun `救场任务插在失败那一步后面并带改道标`() {
        val s = store()
        s.feed(
            """{"t":"plan_created","goal":"g","tasks":[
                 {"id":"t1","type":"COMMAND","description":"一","status":"PENDING","parentId":null,"dependsOn":[]},
                 {"id":"t2","type":"COMMAND","description":"二","status":"PENDING","parentId":null,"dependsOn":[]}],
               "layers":[["t1","t2"]]}""",
            """{"t":"plan_revised","failedTaskId":"t1","tasks":[
                 {"id":"r1","type":"COMMAND","description":"救","status":"PENDING","parentId":null,"dependsOn":[]}]}""",
        )
        val plan = (s.state.value.entries.single() as Entry.Plan).plan
        assertEquals(listOf("t1", "r1", "t2"), plan.tasks.map { it.id })
        assertTrue(plan.tasks[1].recovery)
    }

    @Test
    fun `流水线座位表长大而不是重建`() {
        val s = store()
        s.feed(
            """{"t":"team_started","goal":"g","reason":"分工","seats":[
                 {"id":"s1","role":"scout","roleTitle":"侦察","summary":"拟方案","status":"PENDING"}]}""",
            """{"t":"team_seats_added","seats":[
                 {"id":"s2","role":"coder","roleTitle":"编码","summary":"施工","status":"PENDING"}]}""",
            """{"t":"team_seat_finished","seatId":"s1","role":"scout","status":"SUCCEEDED","durationMs":5,"opinion":"方案","error":null}""",
        )
        val team = (s.state.value.entries.single() as Entry.Team).team
        assertEquals(listOf("s1", "s2"), team.seats.map { it.id })
        assertEquals("方案", team.seats[0].opinion)
    }

    @Test
    fun `reset_done 清空转录并收掉 continue 入口`() {
        val s = store()
        s.feed(
            """{"t":"ready","protocol":1,"version":"x","workspace":"/w","model":"m",
               "planMode":false,"hasApiKey":true,"busy":false,"lastSeq":0,"tools":[],
               "continuable":{"id":"a","turns":1,"messages":2,"firstMessage":"f","savedAt":1,"count":1}}""",
            """{"t":"text_delta","text":"hi"}""",
            """{"t":"reset_done"}""",
        )
        assertNull(s.state.value.ready!!.continuable)
        // 清空之后只剩「对话已清空」那条本地提示
        assertTrue(s.state.value.entries.all { it is Entry.Notice })
    }

    @Test
    fun `replay_gap 落成一条警告，别装完整`() {
        val s = store()
        s.feed("""{"t":"replay_gap","from":5,"to":9}""")
        val notice = s.state.value.entries.single() as Entry.Notice
        assertEquals(Entry.Notice.Level.WARN, notice.level)
        assertTrue(notice.text.contains("5"))
    }
}
