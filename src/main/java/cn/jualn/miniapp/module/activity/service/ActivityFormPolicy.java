package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded data definitions only; no executable expressions or workflow rules.
 */
public final class ActivityFormPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("text", "single_select", "multi_select");
    private static final Set<String> PURPOSES = Set.of("NAME", "STUDENT_NUMBER", "CLASS", "PHONE", "CUSTOM");

    private ActivityFormPolicy() {
    }

    public static JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return JSON.readTree(value);
        } catch (java.io.IOException e) {
            throw invalid("表单数据格式错误");
        }
    }

    public static void schema(JsonNode schema) {
        size(schema);
        keys(schema, Set.of("fields", "allowModification"));
        require(schema.has("allowModification") && schema.get("allowModification").isBoolean(),
                "allowModification必须是布尔值");
        JsonNode fields = schema.get("fields");
        require(fields != null && fields.isArray() && !fields.isEmpty() && fields.size() <= 30, "表单需要1至30个字段");
        Set<String> names = new HashSet<>();
        for (JsonNode f : fields) {
            keys(f, Set.of("fieldKey", "label", "purpose", "type", "required", "helpText", "options", "maxLength", "displayOrder"));
            String key = fieldKey(f);
            require(names.add(key), "fieldKey必须唯一");
            text(f.get("label"), 120);
            require(f.has("purpose") && f.get("purpose").isTextual()
                    && PURPOSES.contains(f.get("purpose").asText()), "purpose不合法");
            String type = fieldType(f);
            require(TYPES.contains(type), "不支持的字段类型");
            require(f.has("required") && f.get("required").isBoolean(), "required必须是布尔值");
            boolean select = type.equals("single_select") || type.equals("multi_select");
            require(f.has("displayOrder") && f.get("displayOrder").isIntegralNumber()
                            && f.get("displayOrder").canConvertToInt() && f.get("displayOrder").asInt() >= 0,
                    "displayOrder必须是非负整数");
            if (f.has("helpText")) text(f.get("helpText"), 500);
            if (select) {
                JsonNode options = f.get("options");
                require(options != null && options.isArray() && !options.isEmpty() && options.size() <= 100, "选项需要1至100项");
                Set<String> values = new HashSet<>();
                for (JsonNode option : options) {
                    keys(option, Set.of("optionKey", "label"));
                    require(values.add(optionKey(option)), "optionKey不能重复");
                    text(option.get("label"), 120);
                }
                require(!f.has("maxLength"), "选择字段不允许maxLength");
            } else {
                require(!f.has("options"), "文本字段不允许options");
                require(f.has("maxLength") && f.get("maxLength").isIntegralNumber()
                        && f.get("maxLength").canConvertToInt() && f.get("maxLength").asInt() > 0
                        && f.get("maxLength").asInt() <= 2000, "TEXT的maxLength范围为1至2000");
            }
        }
    }

    public static void answers(JsonNode schema, JsonNode data) {
        schema(schema);
        size(data);
        require(data.isObject(), "答案必须是对象");
        Set<String> known = new HashSet<>();
        for (JsonNode field : schema.get("fields")) {
            String key = fieldKey(field);
            known.add(key);
            JsonNode value = data.get(key);
            boolean required = field.get("required").asBoolean();
            if (value == null || value.isNull()) {
                require(!required, "必填字段未填写: " + key);
                continue;
            }
            String type = fieldType(field);
            switch (type) {
                case "text" -> {
                    require(value.isTextual(), "文本类型错误: " + key);
                    require(!value.asText().isBlank(), "文本答案不能为空: " + key);
                    require(value.asText().length() <= field.get("maxLength").asInt(), "文本超长: " + key);
                }
                case "single_select", "multi_select" -> {
                    Set<String> allowed = new HashSet<>();
                    field.get("options").forEach(o -> allowed.add(optionKey(o)));
                    if (type.equals("single_select"))
                        require(value.isTextual() && allowed.contains(value.asText()), "无效选项: " + key);
                    else {
                        require(value.isArray() && !value.isEmpty() && value.size() <= allowed.size(), "多选类型或数量错误: " + key);
                        Set<String> selected = new HashSet<>();
                        for (JsonNode v : value)
                            require(v.isTextual() && allowed.contains(v.asText()) && selected.add(v.asText()), "多选包含重复或无效选项: " + key);
                    }
                }
                default -> throw invalid("不支持的字段类型");
            }
        }
        data.fieldNames().forEachRemaining(key -> require(known.contains(key), "未定义字段: " + key));
    }

    public static String display(JsonNode field, JsonNode value) {
        if (value == null || value.isNull()) return "";
        String type = fieldType(field);
        if (type.equals("checkbox")) return value.asBoolean() ? "是" : "否";
        if (type.equals("single_select") || type.equals("multi_select")) {
            Set<String> selected = new HashSet<>();
            if (value.isArray()) value.forEach(v -> selected.add(v.asText()));
            else selected.add(value.asText());
            java.util.List<String> labels = new java.util.ArrayList<>();
            field.get("options").forEach(o -> {
                if (selected.contains(optionKey(o))) labels.add(o.get("label").asText());
            });
            return String.join("、", labels);
        }
        return value.asText();
    }

    public static JsonNode normalizedAnswers(JsonNode schema, JsonNode data) {
        answers(schema, data);
        var normalized = JSON.createObjectNode();
        for (JsonNode field : schema.get("fields")) {
            String key = fieldKey(field);
            JsonNode value = data.get(key);
            if (value == null || value.isNull()) continue;
            if (fieldType(field).equals("multi_select")) {
                var array = normalized.putArray(key);
                Set<String> selected = new HashSet<>();
                value.forEach(v -> selected.add(v.asText()));
                field.get("options").forEach(o -> {
                    if (selected.contains(optionKey(o))) array.add(optionKey(o));
                });
            } else if (value.isNumber()) {
                normalized.set(key, com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros()));
            } else normalized.set(key, value.deepCopy());
        }
        return normalized;
    }

    private static void size(JsonNode node) {
        require(node != null && node.toString().getBytes(StandardCharsets.UTF_8).length <= 65536, "表单JSON不能超过64KiB");
    }

    private static String text(JsonNode node, int max) {
        require(node != null && node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max, "表单文本为空或超长");
        return node.asText();
    }

    public static String fieldKey(JsonNode field) {
        return text(field.get("fieldKey"), 128);
    }

    public static String optionKey(JsonNode option) {
        return text(option.get("optionKey"), 128);
    }

    public static String fieldType(JsonNode field) {
        String raw = text(field.get("type"), 32);
        return switch (raw) {
            case "TEXT" -> "text";
            case "SINGLE_SELECT" -> "single_select";
            case "MULTI_SELECT" -> "multi_select";
            default -> raw;
        };
    }

    public static String keyOf(JsonNode field) {
        return fieldKey(field);
    }

    public static String typeOf(JsonNode field) {
        return fieldType(field);
    }

    private static void keys(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject(), "表单定义必须为对象");
        node.fieldNames().forEachRemaining(k -> require(allowed.contains(k), "表单定义包含未知属性: " + k));
    }

    public static void require(boolean valid, String message) {
        if (!valid) throw invalid(message);
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, message);
    }
}
