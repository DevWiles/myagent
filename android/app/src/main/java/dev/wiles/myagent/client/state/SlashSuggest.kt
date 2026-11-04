package dev.wiles.myagent.client.state

import dev.wiles.myagent.client.protocol.SkillInfo

/**
 * 输入框敲 `/` 时的技能补全，和终端那边的斜杠菜单同一个判据（tui/src/commands.ts）：
 * 只在还在敲名字的时候给（`/` 开头、还没有空格），按名字前缀筛，大小写不敏感。
 * 名字敲完整、后面跟了参数，就不再挡着输入。
 */
object SlashSuggest {

    /** 最多给几条：手机屏窄，多了会把转录顶出屏幕。 */
    const val LIMIT = 4

    fun matches(draft: String, skills: List<SkillInfo>): List<SkillInfo> {
        if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
        val typed = draft.substring(1).lowercase()
        val hits = skills.filter { it.id.lowercase().startsWith(typed) }
        // 已经一字不差敲完了，就不必再提示同一条
        if (hits.size == 1 && hits[0].id.equals(typed, ignoreCase = true)) return emptyList()
        return hits.take(LIMIT)
    }

    /** 点了一条：补全名字，留个空格让人接着敲参数。 */
    fun complete(skill: SkillInfo): String = "/${skill.id} "
}
