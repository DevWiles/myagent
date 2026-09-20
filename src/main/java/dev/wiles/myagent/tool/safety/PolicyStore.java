package dev.wiles.myagent.tool.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 授权规则的落盘：{@code ~/.myagent/policy.json}。
 *
 * <h3>为什么只有用户级，没有项目级</h3>
 * 项目级的 {@code <工作区>/.myagent/policy.json} 听起来更「企业」——团队共享、进版本库、
 * 可审计。问题在于它引入一个真实的攻击面：<b>agent 读的仓库和定策略的仓库是同一个</b>。
 * 一个 PR 往 allow 里塞一条规则，下一个人在这个仓库里跑 agent 时就已经被授权了，
 * 而他不会去读那个文件。让仓库里的文件给自己放行，等于没有策略。
 *
 * <p>真要做，硬规矩是「项目级只能收紧（deny），放宽（allow）一律无效」。
 * 那是另一件事，不在这一轮里。
 *
 * <h3>文件格式是给手改准备的</h3>
 * 授权只能加不能减是不行的——用户会因为「批错了撤不掉」而根本不敢按「以后都同意」。
 * 这一轮没做 {@code /approvals} 这种界面命令，所以撤销的办法就是<b>打开这个文件删一行</b>。
 * 因此格式要平、要能看懂、每条带上加进来的日期，而不是一串紧凑的编码。
 *
 * <pre>
 * {
 *   "version": 1,
 *   "allow": [ { "tool": "bash", "match": "mvn -o test *", "added": "2026-09-20" } ],
 *   "deny":  [ { "tool": "bash", "match": "git push *" } ]
 * }
 * </pre>
 *
 * <h3>读坏文件不许崩</h3>
 * 这个文件用户随时会手改，改坏是常态。坏掉的后果必须是<b>退回「每次都问」</b>，
 * 而不是启动失败，也不是静默放行——前者只是烦，后者才是事故。
 */
public final class PolicyStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 文件格式版本。以后改结构时靠它判断要不要迁移。 */
    static final int VERSION = 1;

    private final Path file;

    public PolicyStore(Path file) {
        this.file = file;
    }

    /** 默认位置：{@code ~/.myagent/policy.json}，和 config.json、plans/、sessions/ 并排。 */
    public static PolicyStore inUserHome() {
        return new PolicyStore(Path.of(System.getProperty("user.home"), ".myagent", "policy.json"));
    }

    public Path file() {
        return file;
    }

    /** 读。文件不在、读不动、写坏了，一律返回空规则集——退化成「每次都问」。 */
    public ApprovalRules load() {
        if (!Files.isRegularFile(file)) {
            return ApprovalRules.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                log.warn("{} 不是一个 JSON 对象，这次当作没有规则", file);
                return ApprovalRules.empty();
            }
            int version = root.path("version").asInt(VERSION);
            if (version > VERSION) {
                // 新版本写的文件，这个旧程序看不懂里面的字段。照读会漏掉限制性的东西，
                // 而漏掉一条 deny 比漏掉一条 allow 危险得多，所以整份不认
                log.warn("{} 的 version={} 比本程序认识的 {} 新，这次当作没有规则",
                        file, version, VERSION);
                return ApprovalRules.empty();
            }
            return ApprovalRules.of(readRules(root.path("allow")), readRules(root.path("deny")));
        } catch (IOException | RuntimeException e) {
            log.warn("读 {} 失败（{}），这次当作没有规则", file, e.getMessage());
            return ApprovalRules.empty();
        }
    }

    private List<ApprovalRule> readRules(JsonNode array) {
        List<ApprovalRule> rules = new ArrayList<>();
        if (!array.isArray()) {
            return rules;
        }
        for (JsonNode node : array) {
            ApprovalKind kind = ApprovalKind.of(node.path("tool").asText(null));
            if (kind == null) {
                log.warn("跳过一条 tool 认不出的规则: {}", node);
                continue;                      // 一条坏规则只该毁掉它自己
            }
            try {
                rules.add(new ApprovalRule(kind, node.path("match").asText(""),
                        node.path("added").asText("")));
            } catch (IllegalArgumentException e) {
                log.warn("跳过一条写坏的规则（{}）: {}", e.getMessage(), node);
            }
        }
        return rules;
    }

    /**
     * 写。临时文件 + 原子替换，理由同 {@code PlanStore.save}：
     * 写到一半进程没了的话，盘上会留下半个 JSON，
     * 下次启动读不出来——而用户看到的现象是「我明明批过」。
     *
     * @return 写成功与否。失败只记日志不抛：授权没存下只是下次再问一次，
     *         不值得为它中断用户正在做的事
     */
    public boolean save(ApprovalRules rules) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", VERSION);
        writeRules(root.putArray("allow"), rules.allow());
        writeRules(root.putArray("deny"), rules.deny());

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            move(tmp, file);
            return true;
        } catch (IOException e) {
            log.warn("写 {} 失败: {}", file, e.getMessage());
            deleteQuietly(tmp);
            return false;
        }
    }

    private static void writeRules(ArrayNode array, List<ApprovalRule> rules) {
        for (ApprovalRule rule : rules) {
            ObjectNode node = array.addObject();
            node.put("tool", rule.kind().id());
            node.put("match", rule.match());
            if (!rule.added().isBlank()) {
                node.put("added", rule.added());
            }
        }
    }

    /**
     * 文件的修改时间，文件不在就是 0。
     *
     * <p>给 {@link PolicyGate} 判断「用户是不是手改过了」用。撤销授权的唯一办法
     * 就是编辑这个文件，要是得重启才生效，用户改完发现照样不问，会以为改错了地方。
     */
    public long lastModified() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 今天的日期，写进新规则的 added 字段。 */
    public static String today() {
        return LocalDate.now().toString();
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不值得打扰任何人
        }
    }
}
