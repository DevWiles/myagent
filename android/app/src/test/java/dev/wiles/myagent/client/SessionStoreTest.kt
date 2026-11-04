package dev.wiles.myagent.client

import dev.wiles.myagent.client.protocol.parseServerEvent
import dev.wiles.myagent.client.state.Entry
import dev.wiles.myagent.client.state.Mode
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
    fun `模式以服务端为准：首帧带着，mode 广播跟着变`() {
        val s = store()
        s.feed(
            """{"t":"ready","protocol":1,"version":"x","workspace":"/w","model":"m",
               "planMode":false,"mode":"plan","hasApiKey":true,"busy":false,"lastSeq":0,"tools":[],
               "skills":[{"id":"deploy","description":"发版"}]}""",
        )
        assertEquals(Mode.PLAN, s.state.value.mode)
        assertEquals("deploy", s.state.value.ready!!.skills.single().id)
        // 模型交的计划被批准，服务端退出 plan 模式
        s.feed("""{"t":"mode","mode":"normal"}""")
        assertEquals(Mode.NORMAL, s.state.value.mode)
    }

    @Test
    fun `计划确认框：带上 kind 和计划全文，别的屏答掉就收`() {
        val s = store()
        s.feed("""{"t":"plan_confirm_request","id":4,"kind":"exit","plan":"## 计划
1. 改 a"}""")
        val confirm = s.state.value.planConfirm!!
        assertEquals("exit", confirm.kind)
        assertTrue(confirm.plan.contains("改 a"))
        // id 对不上的迟到回执不乱收
        s.feed("""{"t":"plan_confirm_resolved","id":3}""")
        assertEquals(4, s.state.value.planConfirm?.id)
        s.feed("""{"t":"plan_confirm_resolved","id":4}""")
        assertNull(s.state.value.planConfirm)
    }

    @Test
    fun `hook 拦下落成错误，hook 自己出问题落成警告`() {
        val s = store()
        s.feed(
            """{"t":"hook","blocked":true,"message":"消息里有密钥"}""",
            """{"t":"hook","blocked":false,"message":"hook「x」退出码 1"}""",
        )
        val (blocked, warned) = s.state.value.entries.map { it as Entry.Notice }
        assertEquals(Entry.Notice.Level.ERROR, blocked.level)
        assertTrue(blocked.text.contains("消息里有密钥"))
        assertEquals(Entry.Notice.Level.WARN, warned.level)
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

class UserEchoTest {

    @Test
    fun `别的界面说的话要画出来`() {
        val s = SessionStore()
        s.apply(parseServerEvent("""{"t":"user_message","text":"终端里说的","mode":"normal","cid":"tui-1"}""")!!)
        val entry = s.state.value.entries.single() as Entry.User
        assertEquals("终端里说的", entry.text)
    }

    @Test
    fun `自己那条跳过——本地已即时回显`() {
        val s = SessionStore()
        s.submitPrompt("我说的")
        s.apply(parseServerEvent(
            """{"t":"user_message","text":"我说的","mode":"normal","cid":"${s.clientId}"}""")!!)
        assertEquals(1, s.state.value.entries.size)
    }
}

/** 断在半路之后提示「继续」：用例和 tui/src/store.test.ts 那组逐条对应。 */
class ContinueHintTest {

    private fun SessionStore.feed(vararg lines: String) {
        for (line in lines) apply(parseServerEvent(line)!!)
    }

    private fun store() = SessionStore()


    private val cut = """{"reason":"已取消","prompt":"把 Main 改了","steps":3,"at":1758100000000}"""

    @Test
    fun `failed 带断点，原因之后紧跟一行「继续」提示`() {
        val s = store()
        s.feed("""{"t":"failed","reason":"已取消","interrupted":$cut}""")
        val notices = s.state.value.entries.filterIsInstance<Entry.Notice>().map { it.text }
        assertEquals(
            listOf("已取消", "现场还在（断在第 3 步）· 说「继续」接着做，或者直接交代新的要求"),
            notices,
        )
        assertEquals("把 Main 改了", s.state.value.interrupted?.prompt)
    }

    @Test
    fun `续不了的失败不提示`() {
        val s = store()
        s.feed("""{"t":"failed","reason":"上下文超限"}""")
        assertEquals(1, s.state.value.entries.size)
        assertNull(s.state.value.interrupted)
    }

    @Test
    fun `下一句话一跑起来断点就作废`() {
        val s = store()
        s.feed(
            """{"t":"failed","reason":"已取消","interrupted":$cut}""",
            """{"t":"busy","busy":true}""",
        )
        assertNull(s.state.value.interrupted)
    }

    @Test
    fun `晚连上来从 ready 拿断点`() {
        val s = store()
        s.feed(
            """{"t":"ready","protocol":1,"version":"x","workspace":"/w","model":"m",""" +
                """"planMode":false,"hasApiKey":true,"busy":false,"interrupted":$cut}""",
        )
        assertEquals(3, s.state.value.interrupted?.steps)
    }

    @Test
    fun `接回来的会话断在半路，「接上了」后面紧跟「继续」提示`() {
        val s = store()
        s.feed(
            """{"t":"session_restored","id":"20260918-143210-3f9a","messages":4,"turns":1,""" +
                """"firstMessage":"把 Main 改了","savedAt":1,"interrupted":$cut}""",
        )
        val last = s.state.value.entries.filterIsInstance<Entry.Notice>().last().text
        assertEquals("现场还在（断在第 3 步）· 说「继续」接着做，或者直接交代新的要求", last)
        assertEquals(3, s.state.value.interrupted?.steps)
    }

    @Test
    fun `接回来的会话没断过，清掉上一段的断点`() {
        val s = store()
        s.feed(
            """{"t":"failed","reason":"已取消","interrupted":$cut}""",
            """{"t":"session_restored","id":"x","messages":4,"turns":1,"firstMessage":"x","savedAt":1}""",
        )
        assertNull(s.state.value.interrupted)
    }

    @Test
    fun `清空对话后断点不作数`() {
        val s = store()
        s.feed(
            """{"t":"failed","reason":"已取消","interrupted":$cut}""",
            """{"t":"reset_done"}""",
        )
        assertNull(s.state.value.interrupted)
    }
}
