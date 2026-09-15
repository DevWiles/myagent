package dev.wiles.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 从模型传来的参数 JSON 里安全取值。
 *
 * 模型传参不总是守规矩：必填字段可能漏、数字可能写成字符串、
 * 类型可能对不上。这里统一处理，缺参数就抛 IllegalArgumentException，
 * 由 ToolRegistry 转成给模型的提示，让它自己补齐重来。
 */
public final class Args {

    private Args() {
    }

    public static String requireString(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 " + field);
        }
        return node.asText();
    }

    public static String optString(JsonNode args, String field, String defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asText();
    }

    /** 模型经常把数字写成字符串，asInt 对 "10" 也能正确解析。 */
    public static int optInt(JsonNode args, String field, int defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asInt(defaultValue);
    }

    public static boolean optBoolean(JsonNode args, String field, boolean defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean(defaultValue);
    }

    public static List<String> optStringList(JsonNode args, String field) {
        JsonNode node = args.get(field);
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        } else {
            result.add(node.asText());   // 只给一个值时模型常常不套数组
        }
        return result;
    }

    public static JsonNode requireArray(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("参数 " + field + " 必须是一个数组");
        }
        return node;
    }
}
