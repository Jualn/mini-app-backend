package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns untrusted model JSON into complete, canonical Draft-property candidates. */
@Component
public class DocumentImportSuggestionValidator {
    private static final Set<String> ACTIVITY = Set.of("title", "summary", "category", "organizer", "audienceScope",
            "audienceSummary", "primaryLocation", "registrationMode", "participantMode", "capacity", "capacityUnit",
            "registrationForm", "timeline", "sections", "actions", "contacts");
    private static final Set<String> PUBLIC_EVENT = Set.of("title", "summary", "type", "sourceName", "sourceUrl",
            "officialUrl", "timeline", "sections", "actions", "contacts");
    private static final Set<String> TIMELINE_TYPES = Set.of("REGISTRATION_START", "REGISTRATION_END",
            "MATERIAL_SUBMISSION", "PRELIMINARY", "SEMIFINAL", "FINAL", "EXAM", "RESULT",
            "CERTIFICATE_COLLECTION", "ADMISSION_TICKET", "OTHER");
    private static final Set<String> ACTION_TYPES = Set.of("JOIN_GROUP", "OFFICIAL_SITE", "EXTERNAL_REGISTRATION",
            "DOWNLOAD", "VIEW_ATTACHMENT", "EMAIL_SUBMISSION", "OFFICIAL_NOTICE", "OTHER");

    private final ObjectMapper mapper;

    public DocumentImportSuggestionValidator(@Qualifier("objectMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ValidationResult validate(String raw, DocumentImportTarget target, String sourceText, String importId) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception exception) {
            throw new InvalidModelOutputException();
        }
        if (!root.isObject() || !only(root, Set.of("suggestions")) || !root.path("suggestions").isArray()) {
            throw new InvalidModelOutputException();
        }

        List<Candidate> accepted = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int ordinal = 0;
        for (JsonNode item : root.path("suggestions")) {
            ordinal++;
            String targetPointer = item.path("target").isTextual() ? item.path("target").asText() : null;
            try {
                if (!item.isObject() || !only(item, Set.of("target", "value", "excerpts"))
                        || targetPointer == null || !targetPointer.startsWith("/") || targetPointer.indexOf('/', 1) >= 0
                        || !item.has("value") || !item.path("excerpts").isArray()) {
                    throw invalid("候选结构不完整");
                }
                String field = targetPointer.substring(1);
                if (!(target == DocumentImportTarget.ACTIVITY ? ACTIVITY : PUBLIC_EVENT).contains(field)
                        || !seen.add(field)) throw invalid("目标字段不允许或重复");
                List<String> excerpts = excerpts(item.path("excerpts"), sourceText);
                JsonNode value = canonicalValue(field, item.path("value"), target, importId, ordinal);
                accepted.add(new Candidate(targetPointer, value, excerpts));
            } catch (CandidateException exception) {
                warnings.add(new Warning("INVALID_SUGGESTION", validPointer(targetPointer),
                        "已省略不符合当前 Draft 约束的候选。"));
            }
        }
        crossValidate(accepted, warnings);
        return new ValidationResult(List.copyOf(accepted), List.copyOf(warnings));
    }

    private JsonNode canonicalValue(String field, JsonNode value, DocumentImportTarget target,
                                    String importId, int ordinal) {
        return switch (field) {
            case "title" -> text(value, 200);
            case "summary" -> text(value, 1000);
            case "category" -> enumeration(value, Set.of("LECTURE", "COMPETITION", "SPORTS", "VOLUNTEERING", "THEMED", "OTHER"));
            case "organizer", "sourceName" -> text(value, 200);
            case "audienceSummary" -> text(value, 200);
            case "primaryLocation" -> text(value, 300);
            case "registrationMode" -> enumeration(value, Set.of("NONE", "MINI_PROGRAM", "EXTERNAL", "MINI_PROGRAM_AND_EXTERNAL"));
            case "participantMode" -> enumeration(value, Set.of("INDIVIDUAL", "TEAM"));
            case "capacity" -> positiveInteger(value);
            case "capacityUnit" -> enumeration(value, Set.of("PERSON", "TEAM"));
            case "type" -> enumeration(value, Set.of("EXAM", "COMPETITION", "CERTIFICATION", "OTHER"));
            case "sourceUrl", "officialUrl" -> httpUrl(value);
            case "audienceScope" -> audience(value);
            case "timeline" -> timeline(value, importId + "-" + ordinal);
            case "sections" -> sections(value, importId + "-" + ordinal);
            case "actions" -> actions(value, importId + "-" + ordinal);
            case "contacts" -> contacts(value, importId + "-" + ordinal);
            case "registrationForm" -> registrationForm(value, importId + "-" + ordinal);
            default -> throw invalid("未知目标");
        };
    }

    private JsonNode audience(JsonNode value) {
        if (!value.isObject() || !only(value, Set.of("type")) || !"CAMPUS".equals(value.path("type").asText())) {
            throw invalid("不能猜测学科部资源 ID");
        }
        return value.deepCopy();
    }

    private JsonNode timeline(JsonNode value, String prefix) {
        ArrayNode result = nonEmptyArray(value, 100);
        ArrayNode normalized = mapper.createArrayNode();
        int index = 0;
        for (JsonNode node : result) {
            if (!node.isObject() || !only(node, Set.of("type", "title", "description", "schedule", "location"))) {
                throw invalid("时间节点属性非法");
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("nodeKey", key(prefix, "n", index));
            out.set("type", enumeration(node.path("type"), TIMELINE_TYPES));
            out.set("title", text(node.path("title"), 120));
            optionalText(node, out, "description", 2000);
            out.set("schedule", schedule(node.path("schedule")));
            optionalText(node, out, "location", 300);
            out.put("displayOrder", index++);
            normalized.add(out);
        }
        return normalized;
    }

    private JsonNode schedule(JsonNode value) {
        if (!value.isObject() || !value.path("kind").isTextual()) throw invalid("时间结构非法");
        String kind = value.path("kind").asText();
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", kind);
        try {
            switch (kind) {
                case "EXACT_POINT" -> {
                    requireOnly(value, Set.of("kind", "time"));
                    out.put("time", OffsetDateTime.parse(requiredText(value, "time", 64)).toString());
                }
                case "EXACT_RANGE" -> {
                    requireOnly(value, Set.of("kind", "startTime", "endTime"));
                    OffsetDateTime start = OffsetDateTime.parse(requiredText(value, "startTime", 64));
                    OffsetDateTime end = OffsetDateTime.parse(requiredText(value, "endTime", 64));
                    if (end.isBefore(start)) throw invalid("精确区间逆序");
                    out.put("startTime", start.toString());
                    out.put("endTime", end.toString());
                }
                case "DATE_POINT" -> {
                    requireOnly(value, Set.of("kind", "date"));
                    out.put("date", LocalDate.parse(requiredText(value, "date", 10)).toString());
                }
                case "DATE_RANGE" -> {
                    requireOnly(value, Set.of("kind", "startDate", "endDate"));
                    LocalDate start = LocalDate.parse(requiredText(value, "startDate", 10));
                    LocalDate end = LocalDate.parse(requiredText(value, "endDate", 10));
                    if (end.isBefore(start)) throw invalid("日期区间逆序");
                    out.put("startDate", start.toString());
                    out.put("endDate", end.toString());
                }
                case "TEXT" -> {
                    requireOnly(value, Set.of("kind", "timeDescription"));
                    out.put("timeDescription", requiredText(value, "timeDescription", 500));
                }
                default -> throw invalid("未知时间精度");
            }
        } catch (DateTimeParseException exception) {
            throw invalid("时间格式非法");
        }
        return out;
    }

    private JsonNode sections(JsonNode value, String prefix) {
        ArrayNode input = nonEmptyArray(value, 50);
        ArrayNode result = mapper.createArrayNode();
        int index = 0;
        for (JsonNode node : input) {
            requireOnly(node, Set.of("title", "content", "format"));
            ObjectNode out = mapper.createObjectNode();
            out.put("sectionKey", key(prefix, "s", index));
            out.set("title", text(node.path("title"), 120));
            String content = textual(node.path("content"), 50000);
            String format = enumeration(node.path("format"), Set.of("PLAIN_TEXT", "MARKDOWN")).asText();
            if ("MARKDOWN".equals(format) && (content.matches("(?s).*<[/!A-Za-z][^>]*>.*")
                    || content.toLowerCase(java.util.Locale.ROOT).contains("javascript:")
                    || content.toLowerCase(java.util.Locale.ROOT).contains("data:"))) {
                throw invalid("Markdown 包含不安全内容");
            }
            out.put("content", content);
            out.put("format", format);
            out.put("displayOrder", index++);
            result.add(out);
        }
        return result;
    }

    private JsonNode actions(JsonNode value, String prefix) {
        ArrayNode input = nonEmptyArray(value, 50);
        ArrayNode result = mapper.createArrayNode();
        int index = 0;
        for (JsonNode node : input) {
            requireOnly(node, Set.of("type", "title", "description", "url"));
            ObjectNode out = mapper.createObjectNode();
            out.put("actionKey", key(prefix, "a", index));
            String type = enumeration(node.path("type"), ACTION_TYPES).asText();
            out.put("type", type);
            out.set("title", text(node.path("title"), 120));
            optionalText(node, out, "description", 2000);
            if (node.has("url")) out.put("url", allowedUrl(node.path("url"), type));
            if (!out.has("description") && !out.has("url")) throw invalid("Action 缺少可执行信息");
            if (Set.of("OFFICIAL_SITE", "EXTERNAL_REGISTRATION", "OFFICIAL_NOTICE").contains(type)
                    && (!out.has("url") || !out.path("url").asText().startsWith("http"))) throw invalid("Action URL 缺失");
            if ("EMAIL_SUBMISSION".equals(type)
                    && (!out.has("url") || !out.path("url").asText().startsWith("mailto:"))) throw invalid("邮件地址缺失");
            if (Set.of("DOWNLOAD", "VIEW_ATTACHMENT").contains(type)
                    && (!out.has("url") || !out.path("url").asText().startsWith("http"))) throw invalid("附件 Action 不可用");
            out.put("displayOrder", index++);
            result.add(out);
        }
        return result;
    }

    private JsonNode contacts(JsonNode value, String prefix) {
        ArrayNode input = nonEmptyArray(value, 20);
        ArrayNode result = mapper.createArrayNode();
        int index = 0;
        for (JsonNode node : input) {
            requireOnly(node, Set.of("name", "contact", "remark"));
            ObjectNode out = mapper.createObjectNode();
            out.put("contactKey", key(prefix, "c", index++));
            out.set("name", text(node.path("name"), 80));
            out.set("contact", text(node.path("contact"), 300));
            optionalText(node, out, "remark", 500);
            result.add(out);
        }
        return result;
    }

    private JsonNode registrationForm(JsonNode value, String prefix) {
        requireOnly(value, Set.of("allowModification", "fields"));
        if (!value.path("allowModification").isBoolean()) throw invalid("报名修改政策缺少依据");
        ArrayNode fields = nonEmptyArray(value.path("fields"), 30);
        ObjectNode result = mapper.createObjectNode();
        result.put("allowModification", value.path("allowModification").asBoolean());
        ArrayNode normalized = mapper.createArrayNode();
        int index = 0;
        for (JsonNode field : fields) {
            requireOnly(field, Set.of("label", "purpose", "type", "required", "helpText", "maxLength", "options"));
            if (!field.path("required").isBoolean()) throw invalid("字段 required 缺少依据");
            ObjectNode out = mapper.createObjectNode();
            out.put("fieldKey", key(prefix, "f", index));
            out.set("label", text(field.path("label"), 120));
            out.set("purpose", enumeration(field.path("purpose"), Set.of("NAME", "STUDENT_NUMBER", "CLASS", "PHONE", "CUSTOM")));
            String type = enumeration(field.path("type"), Set.of("TEXT", "SINGLE_SELECT", "MULTI_SELECT")).asText();
            out.put("type", type);
            out.put("required", field.path("required").asBoolean());
            optionalText(field, out, "helpText", 500);
            if ("TEXT".equals(type)) {
                if (!field.path("maxLength").canConvertToInt() || field.path("maxLength").asInt() < 1
                        || field.path("maxLength").asInt() > 2000 || field.has("options")) throw invalid("文本字段配置非法");
                out.put("maxLength", field.path("maxLength").asInt());
            } else {
                if (field.has("maxLength")) throw invalid("选择字段不能包含 maxLength");
                ArrayNode options = nonEmptyArray(field.path("options"), 100);
                ArrayNode optionResult = mapper.createArrayNode();
                int optionIndex = 0;
                for (JsonNode option : options) {
                    requireOnly(option, Set.of("label"));
                    ObjectNode optionOut = mapper.createObjectNode();
                    optionOut.put("optionKey", key(prefix + "-" + index, "o", optionIndex++));
                    optionOut.set("label", text(option.path("label"), 120));
                    optionResult.add(optionOut);
                }
                out.set("options", optionResult);
            }
            out.put("displayOrder", index++);
            normalized.add(out);
        }
        result.set("fields", normalized);
        return result;
    }

    private void crossValidate(List<Candidate> candidates, List<Warning> warnings) {
        Map<String, Candidate> byTarget = new LinkedHashMap<>();
        candidates.forEach(value -> byTarget.put(value.target(), value));
        boolean capacity = byTarget.containsKey("/capacity");
        boolean unit = byTarget.containsKey("/capacityUnit");
        if (capacity != unit) remove(byTarget, candidates, warnings, Set.of("/capacity", "/capacityUnit"));

        String registration = textValue(byTarget.get("/registrationMode"));
        String participant = textValue(byTarget.get("/participantMode"));
        String capacityUnit = textValue(byTarget.get("/capacityUnit"));
        boolean mini = "MINI_PROGRAM".equals(registration) || "MINI_PROGRAM_AND_EXTERNAL".equals(registration);
        if (registration != null && byTarget.containsKey("/registrationForm") && !mini) {
            remove(byTarget, candidates, warnings, Set.of("/registrationForm"));
        }
        if (mini && participant != null && !"INDIVIDUAL".equals(participant)) {
            remove(byTarget, candidates, warnings, Set.of("/participantMode"));
        }
        if (mini && capacityUnit != null && !"PERSON".equals(capacityUnit)) {
            remove(byTarget, candidates, warnings, Set.of("/capacity", "/capacityUnit"));
        }
        if (participant != null && capacityUnit != null
                && (("INDIVIDUAL".equals(participant) && !"PERSON".equals(capacityUnit))
                || ("TEAM".equals(participant) && !"TEAM".equals(capacityUnit)))) {
            remove(byTarget, candidates, warnings, Set.of("/capacity", "/capacityUnit"));
        }
    }

    private void remove(Map<String, Candidate> byTarget, List<Candidate> candidates, List<Warning> warnings,
                        Set<String> targets) {
        boolean removed = candidates.removeIf(candidate -> targets.contains(candidate.target()));
        targets.forEach(byTarget::remove);
        if (removed) warnings.add(new Warning("INCOMPLETE_CONFIGURATION", null,
                "已省略缺少配对依据或跨字段不一致的配置建议。"));
    }

    private List<String> excerpts(JsonNode value, String source) {
        if (value.isEmpty() || value.size() > 100) throw invalid("来源依据缺失");
        List<String> result = new ArrayList<>();
        for (JsonNode excerpt : value) {
            String text = textual(excerpt, 4000);
            if (!source.contains(text)) throw invalid("来源依据不在提取文本中");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private ArrayNode nonEmptyArray(JsonNode value, int max) {
        if (!value.isArray() || value.isEmpty() || value.size() > max) throw invalid("集合大小非法");
        return (ArrayNode) value;
    }

    private JsonNode text(JsonNode value, int max) {
        return mapper.getNodeFactory().textNode(textual(value, max));
    }

    private String textual(JsonNode value, int max) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw invalid("文本非法");
        return value.asText();
    }

    private JsonNode enumeration(JsonNode value, Set<String> allowed) {
        String actual = textual(value, 128);
        if (!allowed.contains(actual)) throw invalid("枚举非法");
        return mapper.getNodeFactory().textNode(actual);
    }

    private JsonNode positiveInteger(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) throw invalid("整数非法");
        return mapper.getNodeFactory().numberNode(value.asInt());
    }

    private JsonNode httpUrl(JsonNode value) {
        String text = textual(value, 4000);
        try {
            URI uri = URI.create(text);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw invalid("URL 非法");
        } catch (IllegalArgumentException exception) {
            throw invalid("URL 非法");
        }
        return mapper.getNodeFactory().textNode(text);
    }

    private String allowedUrl(JsonNode value, String type) {
        String text = textual(value, 4000);
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme();
            boolean allowed = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || ("EMAIL_SUBMISSION".equals(type) && "mailto".equalsIgnoreCase(scheme));
            if (!allowed) throw invalid("URL scheme 非法");
        } catch (IllegalArgumentException exception) {
            throw invalid("URL 非法");
        }
        return text;
    }

    private void optionalText(JsonNode source, ObjectNode target, String field, int max) {
        if (source.has(field)) target.put(field, textual(source.path(field), max));
    }

    private String requiredText(JsonNode node, String field, int max) {
        return textual(node.path(field), max);
    }

    private void requireOnly(JsonNode value, Set<String> fields) {
        if (!value.isObject() || !only(value, fields)) throw invalid("存在未知或缺失属性");
    }

    private boolean only(JsonNode value, Set<String> fields) {
        if (!value.isObject()) return false;
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    private String key(String prefix, String type, int index) {
        String compact = prefix.replace("-", "");
        if (compact.length() > 16) compact = compact.substring(0, 16);
        return "ai-" + compact + "-" + type + (index + 1);
    }

    private String validPointer(String value) {
        return value != null && value.matches("^/[^/]+$") ? value : null;
    }

    private String textValue(Candidate candidate) {
        return candidate == null || !candidate.value().isTextual() ? null : candidate.value().asText();
    }

    private CandidateException invalid(String message) {
        return new CandidateException(message);
    }

    public record Candidate(String target, JsonNode value, List<String> excerpts) {
    }

    public record Warning(String code, String target, String message) {
    }

    public record ValidationResult(List<Candidate> suggestions, List<Warning> warnings) {
    }

    public static final class InvalidModelOutputException extends RuntimeException {
    }

    private static final class CandidateException extends RuntimeException {
        private CandidateException(String message) {
            super(message);
        }
    }
}
