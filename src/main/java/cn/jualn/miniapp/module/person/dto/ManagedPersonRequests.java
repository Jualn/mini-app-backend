package cn.jualn.miniapp.module.person.dto;

import cn.jualn.miniapp.module.person.bo.ManagedPersonBO;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Set;

import static cn.jualn.miniapp.module.person.PersonProblems.invalid;

/**
 * Closed request parsers; presence and explicit null survive Merge Patch conversion.
 */
public final class ManagedPersonRequests {
    private ManagedPersonRequests() {
    }

    public record Create(ManagedPersonBO.Create command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Create parse(JsonNode n) {
            closed(n, "realName", "majorClass", "reason", "verificationReference");
            return new Create(new ManagedPersonBO.Create(string(n, "realName"), optionalNullable(n, "majorClass"), string(n, "reason"), string(n, "verificationReference")));
        }
    }

    public record Name(ManagedPersonBO.NameChange command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Name parse(JsonNode n) {
            closed(n, "realName", "reason", "verificationReference");
            return new Name(new ManagedPersonBO.NameChange(string(n, "realName"), string(n, "reason"), string(n, "verificationReference")));
        }
    }

    public record Lifecycle(ManagedPersonBO.Lifecycle command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Lifecycle parse(JsonNode n) {
            closed(n, "action", "reason", "verificationReference");
            return new Lifecycle(new ManagedPersonBO.Lifecycle(string(n, "action"), string(n, "reason"), string(n, "verificationReference")));
        }
    }

    public record Registration(ManagedPersonBO.MajorClassPatch command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Registration parse(JsonNode n) {
            closed(n, "majorClass", "reason", "verificationReference");
            return new Registration(new ManagedPersonBO.MajorClassPatch(n.has("majorClass"), optionalNullable(n, "majorClass"), string(n, "reason"), string(n, "verificationReference")));
        }
    }

    public record IdentifierCreate(ManagedPersonBO.IdentifierCreate command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static IdentifierCreate parse(JsonNode n) {
            closed(n, "identifierType", "namespace", "value", "verification", "reason", "verificationReference", "personVersion");
            return new IdentifierCreate(new ManagedPersonBO.IdentifierCreate(string(n, "identifierType"), string(n, "namespace"), string(n, "value"), string(n, "verification"), string(n, "reason"), string(n, "verificationReference"), string(n, "personVersion")));
        }
    }

    public record IdentifierChange(ManagedPersonBO.IdentifierChange command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static IdentifierChange parse(JsonNode n) {
            closed(n, "action", "value", "reason", "verificationReference");
            String action = string(n, "action");
            if ("CORRECT".equals(action) != n.has("value")) throw invalid("value");
            return new IdentifierChange(new ManagedPersonBO.IdentifierChange(action, n.has("value") ? string(n, "value") : null, string(n, "reason"), string(n, "verificationReference")));
        }
    }

    public record Bind(ManagedPersonBO.Bind command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Bind parse(JsonNode n) {
            closed(n, "personId", "userId", "personVersion", "personBindingVersion", "userBindingVersion", "reason", "verificationReference");
            return new Bind(binding(n));
        }
    }

    public record Reassociate(ManagedPersonBO.Reassociate command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Reassociate parse(JsonNode n) {
            closed(n, "endedBindings", "personId", "userId", "personVersion", "personBindingVersion", "userBindingVersion", "reason", "verificationReference");
            var b = binding(n);
            var ended = n.get("endedBindings");
            if (ended == null || !ended.isArray() || ended.isEmpty() || ended.size() > 2)
                throw invalid("endedBindings");
            var versions = new ArrayList<ManagedPersonBO.BindingVersion>();
            for (var e : ended) {
                closed(e, "bindingId", "version");
                versions.add(new ManagedPersonBO.BindingVersion(string(e, "bindingId"), string(e, "version")));
            }
            return new Reassociate(new ManagedPersonBO.Reassociate(versions, b.personId(), b.userId(), b.personVersion(), b.personBindingVersion(), b.userBindingVersion(), b.reason(), b.verificationReference()));
        }
    }

    public record End(ManagedPersonBO.End command) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static End parse(JsonNode n) {
            closed(n, "reason", "verificationReference");
            return new End(new ManagedPersonBO.End(string(n, "reason"), string(n, "verificationReference")));
        }
    }

    private static ManagedPersonBO.Bind binding(JsonNode n) {
        return new ManagedPersonBO.Bind(string(n, "personId"), string(n, "userId"), string(n, "personVersion"), string(n, "personBindingVersion"), string(n, "userBindingVersion"), string(n, "reason"), string(n, "verificationReference"));
    }

    static void closed(JsonNode n, String... fields) {
        if (n == null || !n.isObject()) throw invalid("body");
        var allowed = Set.of(fields);
        n.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) throw invalid(k);
        });
    }

    static String string(JsonNode n, String name) {
        var value = n.get(name);
        if (value == null || !value.isTextual()) throw invalid(name);
        return value.textValue();
    }

    static String optionalNullable(JsonNode n, String name) {
        return !n.has(name) || n.get(name).isNull() ? null : string(n, name);
    }
}
