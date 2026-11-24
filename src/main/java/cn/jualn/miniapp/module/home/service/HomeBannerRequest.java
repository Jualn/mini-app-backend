package cn.jualn.miniapp.module.home.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.home.mapper.HomeBannerMapper.Row;
import cn.jualn.miniapp.module.home.model.HomeContent.Type;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HomeBannerRequest {
    private HomeBannerRequest() {}

    private record Key(Type type, long id) {}

    static List<Row> parse(JsonNode body) {
        if (!fields(body, Set.of("items")) || !body.path("items").isArray() || body.path("items").size() > 3)
            throw invalid("#/items", "INVALID_VALUE");
        List<Row> rows = new ArrayList<>();
        Set<Key> seen = new HashSet<>();
        for (var item : body.path("items")) {
            String path = "#/items/" + rows.size();
            if (!fields(item, Set.of("target", "enabled", "titleOverride", "coverAttachmentId"))
                    || !item.path("enabled").isBoolean()) throw invalid(path, "INVALID_VALUE");
            var target = item.path("target");
            if (!fields(target, Set.of("targetType", "targetId")) || !target.path("targetType").isTextual())
                throw invalid(path + "/target", "INVALID_VALUE");
            Type type;
            try {
                type = Type.valueOf(target.path("targetType").textValue());
            } catch (IllegalArgumentException e) {
                throw invalid(path + "/target/targetType", "INVALID_VALUE");
            }
            long id = reference(target.path("targetId"), path + "/target/targetId");
            if (!seen.add(new Key(type, id))) throw invalid(path + "/target", "DUPLICATE_KEY");
            String title = item.has("titleOverride") ? text(item.path("titleOverride"), path + "/titleOverride", 200) : null;
            if (title != null && title.isBlank()) throw invalid(path + "/titleOverride", "INVALID_VALUE");
            Long cover = item.has("coverAttachmentId") ? reference(item.path("coverAttachmentId"), path + "/coverAttachmentId") : null;
            rows.add(new Row(type, id, item.path("enabled").booleanValue(), title, cover));
        }
        return List.copyOf(rows);
    }

    private static boolean fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private static long reference(JsonNode node, String path) {
        Long id = AdminQuery.storedId(text(node, path, 128));
        if (id == null) throw invalid(path, "INVALID_REFERENCE");
        return id;
    }

    private static String text(JsonNode node, String path, int max) {
        if (!node.isTextual()) throw invalid(path, "INVALID_VALUE");
        String text = node.textValue();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) throw invalid(path, "INVALID_VALUE");
        return text;
    }

    static ContractProblemException invalid(String pointer, String code) {
        return ContractProblemException.validation(new ContractProblemException.Violation(
                "body", pointer, code, "Invalid home banner configuration"));
    }
}
