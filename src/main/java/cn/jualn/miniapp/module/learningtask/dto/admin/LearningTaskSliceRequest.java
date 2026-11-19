package cn.jualn.miniapp.module.learningtask.dto.admin;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Set;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;

public final class LearningTaskSliceRequest {
    private LearningTaskSliceRequest() {
    }

    @JsonDeserialize(using = PreviewJson.class)
    public record Preview(Purpose purpose, LearningTaskDraftRequest.Audience audience) {
    }

    @JsonDeserialize(using = ApplyJson.class)
    public record Apply(String previewId, String reason) {
    }

    @JsonDeserialize(using = ConfirmJson.class)
    public record Confirm(boolean confirmed) {
    }

    @JsonDeserialize(using = LifecycleJson.class)
    public record Lifecycle(java.time.OffsetDateTime firstDeadline, String reason, boolean allowParticipated,
                            boolean participationPresent) {
    }

    public static class LifecycleJson extends JsonDeserializer<Lifecycle> {
        public Lifecycle deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            closed(root, Set.of("firstDeadline", "reason", "allowParticipated"));
            if (!root.path("reason").isTextual()) {
                throw invalid("reason");
            }
            String reason = root.get("reason").textValue();
            if (reason.codePointCount(0, reason.length()) > 1000 || reason.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF)) {
                throw invalid("reason");
            }
            if (root.has("allowParticipated") && !root.get("allowParticipated").isBoolean()) {
                throw invalid("allowParticipated");
            }
            return new Lifecycle(root.has("firstDeadline") ? LearningTaskDraftJson.time(root.get("firstDeadline")) : null, reason, root.path("allowParticipated").asBoolean(), root.has("allowParticipated"));
        }
    }

    public static class PreviewJson extends JsonDeserializer<Preview> {
        public Preview deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            closed(root, Set.of("purpose", "audience"));
            if (!root.path("purpose").isTextual()) {
                throw invalid("purpose");
            }
            Purpose purpose;
            try {
                purpose = Purpose.valueOf(root.get("purpose").textValue());
            } catch (IllegalArgumentException failure) {
                throw invalid("purpose");
            }
            return new Preview(purpose, LearningTaskDraftJson.audience(root.get("audience")));
        }
    }

    public static class ApplyJson extends JsonDeserializer<Apply> {
        public Apply deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            closed(root, Set.of("previewId", "reason"));
            if (!root.path("previewId").isTextual() || root.path("previewId").textValue().isBlank()
                    || root.path("previewId").textValue().length() > 128) {
                throw invalid("previewId");
            }
            String reason = null;
            if (root.has("reason")) {
                if (!root.get("reason").isTextual()) {
                    throw invalid("reason");
                }
                reason = root.get("reason").textValue();
                if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1000) {
                    throw invalid("reason");
                }
            }
            return new Apply(root.get("previewId").textValue(), reason);
        }
    }

    public static class ConfirmJson extends JsonDeserializer<Confirm> {
        public Confirm deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            closed(root, Set.of("confirmed"));
            if (!root.path("confirmed").isBoolean() || !root.get("confirmed").booleanValue()) {
                throw invalid("confirmed");
            }
            return new Confirm(true);
        }
    }

    private static void closed(JsonNode root, Set<String> fields) {
        if (!root.isObject()) {
            throw invalid("body");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) {
                throw invalid(name);
            }
        });
    }
}
