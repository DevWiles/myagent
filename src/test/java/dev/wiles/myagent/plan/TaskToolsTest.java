package dev.wiles.myagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolsTest {

    /** 只要名字对得上就行，这里不关心工具干什么。 */
    private static Tool named(String name) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return "假的 " + name;
            }

            public String parametersSchema() {
                return "{\"type\":\"object\"}";
            }

            public ToolResult execute(JsonNode args) {
                return ToolResult.ok("");
            }
        };
    }

    private static ToolRegistry fullSet() {
        return new ToolRegistry().registerAll(List.of(
                named("read_file"), named("list_dir"), named("write_file"),
                named("bash"), named("create_project"), named("send_email")));
    }

    private static List<String> namesFor(TaskType type) {
        return TaskTools.forType(fullSet(), type).all().stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("读取任务只拿得到只读工具")
    void fileReadGetsReadOnlyTools() {
        assertEquals(List.of("read_file", "list_dir"), namesFor(TaskType.FILE_READ));
    }

    @Test
    @DisplayName("写入任务也得有读工具——不然「改之前先读」这条规矩就破了")
    void fileWriteKeepsReadTools() {
        assertEquals(List.of("read_file", "list_dir", "write_file", "create_project"),
                namesFor(TaskType.FILE_WRITE));
    }

    @Test
    @DisplayName("命令和验证任务拿得到 bash，但拿不到写文件")
    void commandAndVerificationGetBash() {
        assertEquals(List.of("read_file", "list_dir", "bash"), namesFor(TaskType.COMMAND));
        assertEquals(List.of("read_file", "list_dir", "bash"), namesFor(TaskType.VERIFICATION));
    }

    @Test
    @DisplayName("分析任务一个工具都没有：它只该基于上游材料想")
    void analysisGetsNoTools() {
        assertTrue(TaskTools.forType(fullSet(), TaskType.ANALYSIS).isEmpty());
    }

    @Test
    @DisplayName("规划任务只读——它的产出是任务列表，不是代码")
    void planningGetsReadOnlyTools() {
        assertEquals(List.of("read_file", "list_dir"), namesFor(TaskType.PLANNING));
    }

    @Test
    @DisplayName("白名单之外的工具一律不放行，哪怕注册表里有")
    void unknownToolsAreNeverExposed() {
        for (TaskType type : TaskType.values()) {
            assertFalse(namesFor(type).contains("send_email"),
                    type + " 不该看到白名单外的工具");
        }
    }

    @Test
    @DisplayName("注册表里没有的工具不会凭空冒出来")
    void missingToolsAreSkipped() {
        ToolRegistry onlyRead = new ToolRegistry().register(named("read_file"));

        assertEquals(List.of("read_file"),
                TaskTools.forType(onlyRead, TaskType.FILE_WRITE).all().stream()
                        .map(Tool::name).toList());
    }
}
