package cn.jualn.miniapp.third.wx.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Bounded allowlist of protocol structure; never includes content, keyword, identity or credentials. */
final class WxTextSafetyDiagnostics {
    private static final Set<String> VALUES = Set.of("pass", "risky", "review", "reject", "keyword", "content_model");

    private WxTextSafetyDiagnostics() {}

    static String summary(JsonNode body) {
        StringBuilder out = new StringBuilder("root=").append(body.getNodeType());
        fields(out, body, "errcode");
        JsonNode result = body.path("result");
        out.append(" result=").append(result.getNodeType());
        fields(out, result, "suggest", "label");
        JsonNode detail = body.path("detail");
        out.append(" detail=").append(detail.getNodeType()).append(" count=").append(detail.size());
        if (detail.isArray()) {
            for (int i = 0; i < Math.min(16, detail.size()); i++) {
                JsonNode item = detail.get(i);
                out.append(" item[").append(i).append("]=").append(item.getNodeType());
                fields(out, item, "strategy", "errcode", "suggest", "label", "prob", "level");
            }
        }
        return out.toString();
    }

    private static void fields(StringBuilder out, JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            out.append(' ').append(name).append('=').append(value.getNodeType());
            if (value.isIntegralNumber() && value.canConvertToInt()) {
                out.append(':').append(value.intValue());
            } else if (value.isTextual() && VALUES.contains(value.textValue())) {
                out.append(':').append(value.textValue());
            }
        }
    }
}
