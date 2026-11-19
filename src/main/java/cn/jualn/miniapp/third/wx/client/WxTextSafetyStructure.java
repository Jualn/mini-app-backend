package cn.jualn.miniapp.third.wx.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Strict V2 response validation, including the separately gated observed keyword no-hit shape. */
final class WxTextSafetyStructure {
    private WxTextSafetyStructure() {}

    static boolean pass(JsonNode body, boolean allowEmptyKeyword) {
        if (!only(body, Set.of("errcode", "errmsg", "result", "detail", "trace_id"))
                || !number(body.path("errcode"), 0) || !text(body.path("errmsg"), "ok")
                || !body.path("trace_id").isTextual() || body.path("trace_id").textValue().isBlank()) return false;
        JsonNode result = body.path("result");
        if (!only(result, Set.of("suggest", "label")) || !text(result.path("suggest"), "pass")
                || !number(result.path("label"), 100)) return false;
        JsonNode details = body.path("detail");
        if (!details.isArray() || details.isEmpty() || details.size() > 16) return false;
        boolean model = false;
        for (JsonNode item : details) {
            if (!item.isObject() || !number(item.path("errcode"), 0)) return false;
            if (text(item.path("strategy"), "keyword") && item.size() == 2
                    && !item.has("suggest") && !item.has("label")) {
                if (!allowEmptyKeyword) return false;
                continue;
            }
            boolean contentModel = text(item.path("strategy"), "content_model");
            if (!contentModel && !text(item.path("strategy"), "keyword")) return false;
            if (!only(item, Set.of("strategy", "errcode", "suggest", "label", "prob"))
                    || !text(item.path("suggest"), "pass") || !number(item.path("label"), 100)) return false;
            if (item.has("prob") && (!item.path("prob").isIntegralNumber()
                    || !item.path("prob").canConvertToInt() || item.path("prob").intValue() < 0
                    || item.path("prob").intValue() > 100)) return false;
            model |= contentModel;
        }
        return model;
    }

    private static boolean only(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private static boolean number(JsonNode node, int value) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == value;
    }

    private static boolean text(JsonNode node, String value) {
        return node.isTextual() && value.equals(node.textValue());
    }
}
