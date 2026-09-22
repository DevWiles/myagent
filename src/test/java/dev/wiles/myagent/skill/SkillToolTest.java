package dev.wiles.myagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SkillLibrary libraryWith(Path tmp, String id, String content) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("skills").resolve(id));
        Files.writeString(dir.resolve("SKILL.md"), content);
        return SkillLibrary.load(List.of(tmp.resolve("skills")));
    }

    private static ToolResult call(SkillTool tool, String json) throws IOException {
        return tool.execute(MAPPER.readTree(json));
    }

    @Test
    @DisplayName("清单在工具描述里：模型不点也知道有什么、什么时候该点")
    void theListingLivesInTheToolDescription(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "research-report",
                "---\ndescription: 调研某个主题并生成中文报告。\n---\n正文\n"));

        String description = tool.description();

        assertTrue(description.contains("research-report"));
        assertTrue(description.contains("调研某个主题并生成中文报告。"));
        assertFalse(description.contains("正文"), "正文是按需读的，不能挤进每一轮的固定开销");
    }

    @Test
    @DisplayName("点开之后给的是正文，而且必须带上技能目录")
    void invokingReturnsTheBodyAndTheDirectory(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "md-to-pdf",
                "---\ndescription: 转 pdf。\n---\n# 步骤\n先装字体，再跑 scripts/convert.py。\n"));

        ToolResult result = call(tool, "{\"name\":\"md-to-pdf\"}");

        assertTrue(result.ok());
        assertTrue(result.content().contains("先装字体"));
        assertTrue(result.content().contains("md-to-pdf"),
                "目录得给出来——正文里的 scripts/convert.py 是相对它的，"
                        + "不给模型只能拿着相对路径在工作区里瞎找");
    }

    @Test
    @DisplayName("名字认不出时把全部名字列出来——这句话的第一读者是模型")
    void unknownNamesListWhatIsAvailable(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "deploy",
                "---\ndescription: 部署。\n---\n正文\n"));

        ToolResult result = call(tool, "{\"name\":\"deply\"}");

        assertFalse(result.ok());
        assertTrue(result.content().contains("deploy"), "只说「没有这个技能」它只能再猜一次");
    }

    @Test
    @DisplayName("正文里有 !`命令` 时如实说明没执行——否则模型会基于不存在的材料往下编")
    void dynamicCommandsAreDeclaredNotSilentlyDropped(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "summarize",
                "---\ndescription: 总结改动。\n---\n## 当前改动\n\n!`git diff HEAD`\n\n照着总结。\n"));

        String output = call(tool, "{\"name\":\"summarize\"}").content();

        assertTrue(output.contains("1 处"), "得数清楚有几处");
        assertTrue(output.contains("bash"), "还得告诉它怎么自己把那段补上");
    }

    @Test
    @DisplayName("声明了 allowed-tools 就转达，同时说清我们不强制——别让人以为有道不存在的关卡")
    void allowedToolsIsRelayedButNotEnforced(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "git-only",
                "---\ndescription: 只碰 git。\nallowed-tools: Bash(git *)\n---\n正文\n"));

        String output = call(tool, "{\"name\":\"git-only\"}").content();

        assertTrue(output.contains("Bash(git *)"));
        assertTrue(output.contains("不强制"));
    }

    @Test
    @DisplayName("不给 name 不算崩——退回一句能照着改的话")
    void missingNameIsAFriendlyError(@TempDir Path tmp) throws IOException {
        SkillTool tool = new SkillTool(libraryWith(tmp, "deploy",
                "---\ndescription: 部署。\n---\n正文\n"));

        assertFalse(call(tool, "{}").ok());
        assertTrue(call(tool, "{}").content().contains("deploy"));
    }
}
