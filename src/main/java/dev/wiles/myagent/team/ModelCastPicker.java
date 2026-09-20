package dev.wiles.myagent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.llm.ChatMessage;
import dev.wiles.myagent.llm.LlmClient;
import dev.wiles.myagent.plan.AgentRole;
import dev.wiles.myagent.plan.AgentRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 问模型：这个议题该让哪几个角色各看一遍。
 *
 * <h3>为什么这一步不回灌重试</h3>
 * 和 {@code Planner} 不一样。规划的产出是<b>结构</b>，错一个字段整张图就建不起来，
 * 只能打回重写；挑人的产出是一份名单，<b>错了也有一份说得过去的默认组合可用</b>。
 * 用户要的是一场圆桌，不是一次完美的挑人——为了名单再烧两次调用，
 * 还让他多等十几秒，不划算。所以这里的姿势是：能用就用，不能用就退到默认。
 *
 * <h3>模型编出不存在的角色时，只丢它不丢整份名单</h3>
 * 「explorer + code_reviewer」这种输出，前一半是对的。整份作废等于把对的也扔了，
 * 而补齐比重问便宜得多。
 */
public final class ModelCastPicker implements CastPicker {

    private static final Logger log = LoggerFactory.getLogger(ModelCastPicker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 挑人要的是判断不是文采，温度压到和规划一样低。 */
    public static final double TEMPERATURE = 0.2;

    /** 一份名单加一句理由，几百 token 绰绰有余。开了 JSON 模式就必须封顶。 */
    public static final int MAX_TOKENS = 512;

    private final LlmClient client;

    public ModelCastPicker(LlmClient client) {
        this.client = client.jsonMode(true).temperature(TEMPERATURE).maxTokens(MAX_TOKENS);
    }

    @Override
    public void cancel() {
        client.cancel();
    }

    /**
     * 挑一桌人。
     *
     * @param available 这次能坐上桌的角色（只读模式下少一半，见 {@link Roundtable#seatable}）
     */
    @Override
    public Cast pick(String goal, List<AgentRole> available) {
        if (available.isEmpty()) {
            return Cast.of(List.of(), "没有可派的角色");
        }
        try {
            String reply = client.chat(List.of(
                    ChatMessage.system(TeamPrompt.castSystem(available)),
                    ChatMessage.user(TeamPrompt.castUser(goal))), null).text();
            return parse(reply, available);
        } catch (RuntimeException e) {
            log.info("挑人没成（{}），用默认组合", e.toString());
            return fallback(available, "挑人那一步没跑成，用了默认组合");
        }
    }

    private Cast parse(String reply, List<AgentRole> available) {
        JsonNode root = json(reply);
        if (root == null) {
            log.info("挑人的输出不是 JSON，用默认组合: {}", clip(reply));
            return fallback(available, "模型没按格式给名单，用了默认组合");
        }

        List<AgentRole> members = new ArrayList<>();
        JsonNode list = root.get("members");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String id = item.asText("").strip().toLowerCase();
                AgentRole role = AgentRoles.find(id).orElse(null);
                if (role == null) {
                    log.info("挑人挑了个不存在的角色 {}，跳过", id);
                } else if (!available.contains(role)) {
                    // 只读模式下会走到这儿：模型看到的菜单里本来就没有它，但它还是写了
                    log.info("角色 {} 这次坐不上桌，跳过", id);
                } else {
                    members.add(role);
                }
            }
        }

        if (members.size() < Roundtable.MIN_MEMBERS) {
            // 一个人不叫圆桌，那是点名。补齐到下限，而不是退回整份默认名单——
            // 模型挑对的那几个仍然比默认的更贴议题
            for (AgentRole role : available) {
                if (members.size() >= Roundtable.MIN_MEMBERS) {
                    break;
                }
                if (!members.contains(role)) {
                    members.add(role);
                }
            }
        }
        if (members.size() > Roundtable.MAX_MEMBERS) {
            log.info("挑了 {} 位，超过上限，只留前 {} 位", members.size(), Roundtable.MAX_MEMBERS);
            members = members.subList(0, Roundtable.MAX_MEMBERS);
        }
        return Cast.of(members, text(root, "reason"));
    }

    /**
     * 默认组合：名单里最前面的两位。
     *
     * <p>按名册顺序取而不是写死 id，是因为只读模式下能坐的角色本来就少一半——
     * 写死一个「explorer + verifier」的组合，在只读模式下会挑中一个根本派不出去的角色。
     */
    private Cast fallback(List<AgentRole> available, String reason) {
        int size = Math.min(Roundtable.MIN_MEMBERS, available.size());
        return Cast.of(List.copyOf(available.subList(0, size)), reason);
    }

    /**
     * 第一个 <code>{</code> 到最后一个 <code>}</code>——和 {@code PlanParser} 用的是同一招：
     * 模型可能用 ```json 围栏、可能不用，但只要它写了对象，这个区间就一定包住它。
     */
    private static JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText().strip();
    }

    private static String clip(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }
}
