package dev.wiles.myagent.client

import dev.wiles.myagent.client.protocol.SkillInfo
import dev.wiles.myagent.client.state.SlashSuggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashSuggestTest {

    private val skills = listOf(
        SkillInfo("deploy", "发版"),
        SkillInfo("design-review", "设计评审"),
        SkillInfo("baguwen", "八股文"),
    )

    private fun ids(draft: String) = SlashSuggest.matches(draft, skills).map { it.id }

    @Test
    fun onlyWhileTypingTheName() {
        assertEquals(listOf("deploy", "design-review", "baguwen"), ids("/"))
        assertEquals(listOf("deploy", "design-review"), ids("/De"))
        assertTrue("不以斜杠开头不提示", ids("de").isEmpty())
        assertTrue("名字后面开始敲参数就不挡着了", ids("/deploy 生产").isEmpty())
        assertTrue("没有对得上的", ids("/x").isEmpty())
    }

    @Test
    fun exactNameNeedsNoHint() {
        assertTrue(ids("/baguwen").isEmpty())
        assertEquals(listOf("deploy"), ids("/dep"))
    }

    @Test
    fun capsAtLimitAndCompletesWithSpace() {
        val many = (1..10).map { SkillInfo("s$it", "") }
        assertEquals(SlashSuggest.LIMIT, SlashSuggest.matches("/s", many).size)
        assertEquals("/deploy ", SlashSuggest.complete(skills[0]))
    }
}
