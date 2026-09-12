package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounded data definitions only; no executable expressions or workflow rules. */
public final class ActivityFormPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("text", "textarea", "number", "single_select", "multi_select", "checkbox");
    private ActivityFormPolicy() {}

    public static JsonNode parse(String value) {
        if (value == null) return null;
        try { return JSON.readTree(value); }
        catch (java.io.IOException e) { throw invalid("表单数据格式错误"); }
    }

    public static void schema(JsonNode schema) {
        size(schema);
        keys(schema, Set.of("fields"));
        JsonNode fields = schema.get("fields");
        require(fields != null && fields.isArray() && !fields.isEmpty() && fields.size() <= 30, "表单需要1至30个字段");
        Set<String> names = new HashSet<>();
        for (JsonNode f : fields) {
            keys(f, Set.of("key", "label", "type", "required", "options", "maxLength", "min", "max"));
            String key = text(f.get("key"), 64);
            require(key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") && names.add(key), "字段key必须唯一且为字母开头的字母数字下划线");
            text(f.get("label"), 128);
            String type = text(f.get("type"), 32);
            require(TYPES.contains(type), "不支持的字段类型");
            require(f.has("required") && f.get("required").isBoolean(), "required必须是布尔值");
            boolean select = type.equals("single_select") || type.equals("multi_select");
            boolean string = type.equals("text") || type.equals("textarea");
            if (select) {
                JsonNode options = f.get("options");
                require(options != null && options.isArray() && !options.isEmpty() && options.size() <= 50, "选项需要1至50项");
                Set<String> values = new HashSet<>();
                for (JsonNode option : options) {
                    keys(option, Set.of("value", "label"));
                    require(values.add(text(option.get("value"), 64)), "选项value不能重复");
                    text(option.get("label"), 128);
                }
            } else require(!f.has("options"), "此类型不允许选项");
            if (f.has("maxLength")) require(string && f.get("maxLength").isIntegralNumber()
                    && f.get("maxLength").canConvertToInt() && f.get("maxLength").asInt() > 0
                    && f.get("maxLength").asInt() <= 10000, "maxLength仅用于文本且范围1至10000");
            for (String bound : Set.of("min", "max"))
                if (f.has(bound)) require(type.equals("number") && finite(f.get(bound)), "数值范围仅用于有限number");
            if (f.has("min") && f.has("max")) require(f.get("min").decimalValue().compareTo(f.get("max").decimalValue()) <= 0, "数值范围颠倒");
        }
    }

    public static void answers(JsonNode schema, JsonNode data) {
        schema(schema);
        size(data);
        require(data.isObject(), "答案必须是对象");
        Set<String> known = new HashSet<>();
        for (JsonNode field : schema.get("fields")) {
            String key = field.get("key").asText();
            known.add(key);
            JsonNode value = data.get(key);
            boolean required = field.get("required").asBoolean();
            if (value == null || value.isNull()) { require(!required, "必填字段未填写: " + key); continue; }
            String type = field.get("type").asText();
            switch (type) {
                case "text", "textarea" -> {
                    require(value.isTextual(), "文本类型错误: " + key);
                    require(!required || !value.asText().isBlank(), "必填字段未填写: " + key);
                    require(value.asText().length() <= field.path("maxLength").asInt(type.equals("text") ? 255 : 10000), "文本超长: " + key);
                }
                case "number" -> {
                    require(finite(value), "数值类型错误或超出有限数值范围: " + key);
                    if (field.has("min")) require(value.decimalValue().compareTo(field.get("min").decimalValue()) >= 0, "数值小于下限: " + key);
                    if (field.has("max")) require(value.decimalValue().compareTo(field.get("max").decimalValue()) <= 0, "数值超过上限: " + key);
                }
                case "checkbox" -> require(value.isBoolean() && (!required || value.asBoolean()), "确认项必须勾选: " + key);
                case "single_select", "multi_select" -> {
                    Set<String> allowed = new HashSet<>();
                    field.get("options").forEach(o -> allowed.add(o.get("value").asText()));
                    if (type.equals("single_select")) require(value.isTextual() && allowed.contains(value.asText()), "无效选项: " + key);
                    else {
                        require(value.isArray() && value.size() <= allowed.size() && (!required || !value.isEmpty()), "多选类型或数量错误: " + key);
                        Set<String> selected = new HashSet<>();
                        for (JsonNode v : value) require(v.isTextual() && allowed.contains(v.asText()) && selected.add(v.asText()), "多选包含重复或无效选项: " + key);
                    }
                }
                default -> throw invalid("不支持的字段类型");
            }
        }
        data.fieldNames().forEachRemaining(key -> require(known.contains(key), "未定义字段: " + key));
    }

    public static String display(JsonNode field, JsonNode value) {
        if (value == null || value.isNull()) return "";
        String type = field.get("type").asText();
        if (type.equals("checkbox")) return value.asBoolean() ? "是" : "否";
        if (type.equals("single_select") || type.equals("multi_select")) {
            Set<String> selected = new HashSet<>();
            if (value.isArray()) value.forEach(v -> selected.add(v.asText())); else selected.add(value.asText());
            java.util.List<String> labels = new java.util.ArrayList<>();
            field.get("options").forEach(o -> { if (selected.contains(o.get("value").asText())) labels.add(o.get("label").asText()); });
            return String.join("、", labels);
        }
        return value.asText();
    }

    public static JsonNode normalizedAnswers(JsonNode schema, JsonNode data) {
        answers(schema, data);
        var normalized = JSON.createObjectNode();
        for (JsonNode field : schema.get("fields")) {
            String key = field.get("key").asText();
            JsonNode value = data.get(key);
            if (value == null || value.isNull()) continue;
            if (field.get("type").asText().equals("multi_select")) {
                var array = normalized.putArray(key);
                Set<String> selected = new HashSet<>(); value.forEach(v -> selected.add(v.asText()));
                field.get("options").forEach(o -> { if (selected.contains(o.get("value").asText())) array.add(o.get("value").asText()); });
            } else if (value.isNumber()) {
                normalized.set(key, com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros()));
            } else normalized.set(key, value.deepCopy());
        }
        return normalized;
    }

    private static void size(JsonNode node) {
        require(node != null && node.toString().getBytes(StandardCharsets.UTF_8).length <= 65536, "表单JSON不能超过64KiB");
    }
    private static boolean finite(JsonNode node) { return node.isNumber() && Double.isFinite(node.doubleValue()); }
    private static String text(JsonNode node, int max) {
        require(node != null && node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max, "表单文本为空或超长");
        return node.asText();
    }
    private static void keys(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject(), "表单定义必须为对象");
        node.fieldNames().forEachRemaining(k -> require(allowed.contains(k), "表单定义包含未知属性: " + k));
    }
    public static void require(boolean valid, String message) { if (!valid) throw invalid(message); }
    public static BusinessException invalid(String message) { return new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, message); }
}
