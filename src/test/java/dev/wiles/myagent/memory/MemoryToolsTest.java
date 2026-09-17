package dev.wiles.myagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.tool.BuiltinTools;
import dev.wiles.myagent.tool.Tool;
import dev.wiles.myagent.tool.ToolRegistry;
import dev.wiles.myagent.tool.ToolResult;
import dev.wiles.myagent.tool.Workspace;
import dev.wiles.myagent.tool.builtin.RecallTool;
import dev.wiles.myagent.tool.builtin.RememberTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private MemoryStore store() {
        return new MemoryStore(tmp.resolve("memory"));
    }

    private Path ws() {
        return tmp.resolve("proj");
    }

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("remember 写进去，recall 读得出来")
    void rememberThenRecall() {
        MemoryStore store = store();
        RememberTool remember = new RememberTool(store, ws());
        RecallTool recall = new RecallTool(store, ws());

        ToolResult written = remember.execute(args("""
                {"name":"mvn-offline","description":"跑测试要加 -o",
                 "content":"这台机器连 central 很慢，依赖已经下全了","source":"user_correction"}"""));
        assertTrue(written.ok());
        assertTrue(written.content().contains("mvn-offline"));

        ToolResult read = recall.execute(args("{\"name\":\"mvn-offline\"}"));
        assertTrue(read.ok());
        assertTrue(read.content().contains("这台机器连 central 很慢"));
        assertTrue(read.content().contains("user_correction"));
    }

    @Test
    @DisplayName("recall 扑空时把现有名字列回去——只说「没找到」，模型只会换个拼法再试一次")
    void recallMissListsWhatExists() {
        MemoryStore store = store();
        new RememberTool(store, ws()).execute(args("""
                {"name":"build-uses-bun","description":"用 bun","content":"不是 npm"}"""));

        ToolResult miss = new RecallTool(store, ws()).execute(args("{\"name\":\"build-uses-npm\"}"));
        assertFalse(miss.ok());
        assertTrue(miss.content().contains("build-uses-bun"),
                "得把现有的名字给它，它才有可能自己纠正");
    }

    @Test
    @DisplayName("一条记忆都没有时，recall 明说没有，而不是报个含糊的错")
    void recallOnEmptyStore() {
        ToolResult miss = new RecallTool(store(), ws()).execute(args("{\"name\":\"anything\"}"));
        assertFalse(miss.ok());
        assertTrue(miss.content().contains("一条记忆都没有"));
    }

    @Test
    @DisplayName("模型传的名字大小写空格不规范也能存下来，而且查得到")
    void toleratesSloppyNames() {
        MemoryStore store = store();
        new RememberTool(store, ws()).execute(args("""
                {"name":"Mvn Offline","description":"摘要","content":"正文"}"""));

        // 存的时候归一化了，用原样、用归一化后的写法都该查得到
        assertTrue(new RecallTool(store, ws()).execute(args("{\"name\":\"Mvn Offline\"}")).ok());
        assertTrue(new RecallTool(store, ws()).execute(args("{\"name\":\"mvn-offline\"}")).ok());
    }

    @Test
    @DisplayName("参数不合规返 error 而不是抛——上游是不可控的模型输出")
    void badArgsBecomeErrors() {
        RememberTool remember = new RememberTool(store(), ws());

        // 缺必填字段：错误信息里必须点名是哪个字段，模型才补得上
        ToolResult missing = remember.execute(args("""
                {"description":"缺 name","content":"x"}"""));
        assertFalse(missing.ok());
        assertTrue(missing.content().contains("name"));

        // 名字里全是符号，归一化之后什么都不剩
        ToolResult unusable = remember.execute(args("""
                {"name":"!!!","description":"名字归一化之后是空的","content":"x"}"""));
        assertFalse(unusable.ok());

        // 空 description：索引里全靠它判断相关性，写不出摘要的记忆没有存在意义
        ToolResult noSummary = remember.execute(args("""
                {"name":"x","description":"  ","content":"正文"}"""));
        assertFalse(noSummary.ok());

        // 三种都没写进盘里
        assertTrue(store().scan(ws()).isEmpty());
    }

    @Test
    @DisplayName("scope 默认是 project——记错范围的代价是拿 A 项目的结论去改 B 项目")
    void defaultScopeIsProject() {
        MemoryStore store = store();
        new RememberTool(store, ws()).execute(args("""
                {"name":"no-scope-given","description":"摘要","content":"正文"}"""));

        assertEquals(MemoryScope.PROJECT,
                store.load("no-scope-given", ws()).orElseThrow().scope());
        assertTrue(store.scan(tmp.resolve("other")).isEmpty());
    }

    @Test
    @DisplayName("global 记忆在别的工作区也能 recall 到")
    void globalCrossesWorkspaces() {
        MemoryStore store = store();
        new RememberTool(store, ws()).execute(args("""
                {"name":"prefers-cn","description":"始终中文","content":"用户要求的",
                 "scope":"global","source":"user_stated"}"""));

        assertTrue(new RecallTool(store, tmp.resolve("elsewhere"))
                .execute(args("{\"name\":\"prefers-cn\"}")).ok());
    }

    @Test
    @DisplayName("盘写不了时 remember 返 error，并明说不用重试")
    void diskFailureIsReportedNotThrown() throws Exception {
        Path blocked = tmp.resolve("blocked");
        Files.writeString(blocked, "我是个文件");
        RememberTool remember = new RememberTool(new MemoryStore(blocked), ws());

        ToolResult result = remember.execute(args("""
                {"name":"x","description":"摘要","content":"正文"}"""));
        assertFalse(result.ok());
        assertTrue(result.content().contains("不用重试"),
                "环境问题重试也没用，不说清楚模型会一直试");
    }

    // ---------------------------------------------------------------- 注册

    private static List<String> toolNames(ToolRegistry registry) {
        return registry.all().stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("完整模式下 remember 和 recall 都在菜单上")
    void defaultRegistersBothTools() {
        ToolRegistry registry = BuiltinTools.createDefault(
                new Workspace(ws()), null, store());
        assertTrue(toolNames(registry).contains("remember"));
        assertTrue(toolNames(registry).contains("recall"));
    }

    @Test
    @DisplayName("只读模式给 recall 不给 remember")
    void readOnlyGetsRecallOnly() {
        ToolRegistry registry = BuiltinTools.createReadOnly(new Workspace(ws()), store());
        assertFalse(toolNames(registry).contains("remember"),
                "只读模式承诺不改用户任何东西，~/.myagent 也算");
        assertTrue(toolNames(registry).contains("recall"),
                "不给 recall 的话，索引里的摘要行就成了读不到正文的悬空引用");
    }

    @Test
    @DisplayName("不传 MemoryStore 时两个工具都不注册，行为和加记忆之前一样")
    void noMemoryStoreMeansNoMemoryTools() {
        List<String> full = toolNames(BuiltinTools.createDefault(new Workspace(ws()), null));
        assertFalse(full.contains("remember"));
        assertFalse(full.contains("recall"));

        List<String> ro = toolNames(BuiltinTools.createReadOnly(new Workspace(ws())));
        assertFalse(ro.contains("recall"));
    }

    @Test
    @DisplayName("remember 的说明里必须写清什么时候不该记——不写它就会拿一次性细节刷屏")
    void rememberDescriptionSaysWhenNotTo() {
        String description = new RememberTool(store(), ws()).description();
        assertTrue(description.contains("不要"), "只讲什么时候该记，模型会把什么都记下来");
        assertTrue(description.contains("一次性"), "得点名一次性中间结果这个最常见的误记");
    }
}
