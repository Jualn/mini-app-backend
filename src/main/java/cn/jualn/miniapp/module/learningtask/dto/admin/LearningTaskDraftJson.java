package cn.jualn.miniapp.module.learningtask.dto.admin;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest.Audience;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest.Create;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest.Edit;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest.Filter;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest.Requirements;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;

/**
 * Reject coercions, nulls, unknown properties and partial nested replacements before domain conversion.
 */
public final class LearningTaskDraftJson {
    private static final Set<String> FIELDS = Set.of("title", "description", "articleId", "requirements",
            "reviewRequired", "firstDeadline", "audience");

    private LearningTaskDraftJson() {
    }

    public static final class CreateDeserializer extends JsonDeserializer<Create> {
        @Override
        public Create deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            object(root, "", Set.of("title", "description", "articleId", "requirements", "reviewRequired", "firstDeadline", "audience", "managementTarget"));
            return new Create(text(root.get("title"), "title", 1, 120, true),
                    text(root.get("description"), "description", 0, 10000, false),
                    root.has("articleId") ? id(root.get("articleId"), "articleId") : null,
                    requirements(root.get("requirements")), bool(root.get("reviewRequired"), "reviewRequired"),
                    time(root.get("firstDeadline")), root.has("audience") ? audience(root.get("audience")) : null, managementTarget(root.get("managementTarget")));
        }
    }

    public static final class EditDeserializer extends JsonDeserializer<Edit> {
        @Override
        public Edit deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            object(root, "", FIELDS);
            if (root.isEmpty()) {
                throw invalid("patch");
            }
            return new Edit(root.has("title") ? text(root.get("title"), "title", 1, 120, true) : null,
                    root.has("description") ? text(root.get("description"), "description", 0, 10000, false) : null,
                    root.has("articleId"), root.has("articleId") && !root.get("articleId").isNull()
                    ? id(root.get("articleId"), "articleId") : null,
                    root.has("requirements") ? requirements(root.get("requirements")) : null,
                    root.has("reviewRequired") ? bool(root.get("reviewRequired"), "reviewRequired") : null,
                    root.has("firstDeadline") ? time(root.get("firstDeadline")) : null,
                    root.has("managementTarget") ? managementTarget(root.get("managementTarget")) : null,
                    root.has("audience") ? audience(root.get("audience")) : null);
        }
    }

    private static UnifiedModels.Target managementTarget(JsonNode n) {
        if (n == null || !n.isObject()) throw invalid("managementTarget");
        String type = text(n.get("type"), "managementTarget/type", 1, 32, true);
        if ("PLATFORM".equals(type)) {
            object(n, "managementTarget", Set.of("type"));
            return new UnifiedModels.Target(type, null, null);
        }
        if (!"ORGANIZATION".equals(type)) throw invalid("managementTarget/type");
        object(n, "managementTarget", Set.of("type", "organizationId"));
        return new UnifiedModels.Target(type, null, id(n.get("organizationId"), "managementTarget/organizationId"));
    }

    private static Requirements requirements(JsonNode node) {
        String prefix = "requirements/";
        object(node, "requirements", Set.of("confirmationRequired", "minimumTextLength", "minimumFiles", "maximumFiles", "allowedFileKinds"));
        var kinds = strings(node.get("allowedFileKinds"), prefix + "allowedFileKinds", 0, 4, 16);
        if (!Set.of("IMAGE", "VIDEO", "PDF", "WORD").containsAll(kinds)) {
            throw invalid(prefix + "allowedFileKinds");
        }
        return new Requirements(bool(node.get("confirmationRequired"), prefix + "confirmationRequired"),
                integer(node.get("minimumTextLength"), prefix + "minimumTextLength", 0, 10000),
                integer(node.get("minimumFiles"), prefix + "minimumFiles", 0, 9),
                integer(node.get("maximumFiles"), prefix + "maximumFiles", 0, 9), kinds);
    }

    public static Audience audience(JsonNode node) {
        object(node, "audience", Set.of("persons", "accounts"));
        if (!node.has("persons") && !node.has("accounts")) throw invalid("audience");
        LearningTaskDraftRequest.Persons persons = null;
        LearningTaskDraftRequest.Accounts accounts = null;
        if (node.has("persons")) {
            var p = node.get("persons");
            object(p, "audience/persons", Set.of("personIds", "filters", "authorizedScopeId", "organizationSelections"));
            var ids = p.has("personIds") ? strings(p.get("personIds"), "audience/persons/personIds", 1, 1000, 128) : List.<String>of();
            var filters = new ArrayList<Filter>();
            if (p.has("filters")) {
                var values = p.get("filters");
                if (!values.isArray() || values.isEmpty() || values.size() > 20)
                    throw invalid("audience/persons/filters");
                for (var filter : values) {
                    object(filter, "audience/persons/filters", Set.of("organizationIds", "identityIds", "developmentStageIds"));
                    if (filter.isEmpty()) throw invalid("audience/persons/filters");
                    filters.add(new Filter(dimension(filter, "organizationIds"), dimension(filter, "identityIds"), dimension(filter, "developmentStageIds")));
                }
            }
            String scope = p.has("authorizedScopeId") ? id(p.get("authorizedScopeId"), "audience/persons/authorizedScopeId") : null;
            if (ids.isEmpty() && filters.isEmpty() && scope == null) throw invalid("audience/persons");
            var groups = new ArrayList<LearningTaskDraftRequest.OrganizationSelection>();
            if (p.has("organizationSelections")) {
                var values = p.get("organizationSelections");
                if (!values.isArray() || values.isEmpty() || values.size() > 100) throw invalid("audience/persons/organizationSelections");
                var organizations = new HashSet<String>();
                for (var group : values) {
                    object(group, "audience/persons/organizationSelections", Set.of("organizationId", "personIds"));
                    String organization = id(group.get("organizationId"), "audience/persons/organizationSelections/organizationId");
                    var members = strings(group.get("personIds"), "audience/persons/organizationSelections/personIds", 1, 1000, 128);
                    if (!organizations.add(organization) || !ids.containsAll(members)) throw invalid("audience/persons/organizationSelections");
                    groups.add(new LearningTaskDraftRequest.OrganizationSelection(organization, members));
                }
            }
            persons = new LearningTaskDraftRequest.Persons(ids, List.copyOf(filters), scope, List.copyOf(groups));
        }
        if (node.hasNonNull("accounts")) {
            var a = node.get("accounts");
            object(a, "audience/accounts", Set.of("userIds"));
            accounts = new LearningTaskDraftRequest.Accounts(strings(a.get("userIds"), "audience/accounts/userIds", 1, 1000, 128));
        }
        return new Audience(persons, accounts);
    }

    private static List<String> dimension(JsonNode filter, String name) {
        return filter.has(name) ? strings(filter.get(name), "audience/filters/" + name, 1, 1000, 128) : List.of();
    }

    private static List<String> strings(JsonNode node, String field, int minimum, int maximum, int maxLength) {
        if (node == null || !node.isArray() || node.size() < minimum || node.size() > maximum) {
            throw invalid(field);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(text(item, field, 1, maxLength, false));
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw invalid(field);
        }
        return List.copyOf(values);
    }

    private static void object(JsonNode node, String field, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw invalid(field);
        }
        for (var names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!fields.contains(name)) {
                throw invalid(field.isEmpty() ? name : field + "/" + name);
            }
        }
    }

    private static String id(JsonNode node, String field) {
        return text(node, field, 1, 128, false);
    }

    private static String text(JsonNode node, String field, int minimum, int maximum, boolean nonBlank) {
        if (node == null || !node.isTextual()) {
            throw invalid(field);
        }
        String value = node.textValue();
        int size = value.codePointCount(0, value.length());
        if (size < minimum || size > maximum || (nonBlank && value.codePoints().allMatch(
                point -> Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xFEFF))) {
            throw invalid(field);
        }
        return value;
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null || !node.isBoolean()) {
            throw invalid(field);
        }
        return node.booleanValue();
    }

    private static int integer(JsonNode node, String field, int minimum, int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() ||
                node.intValue() < minimum || node.intValue() > maximum) {
            throw invalid(field);
        }
        return node.intValue();
    }

    public static OffsetDateTime time(JsonNode node) {
        String value = text(node, "firstDeadline", 1, 128, false);
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})")) {
            throw invalid("firstDeadline");
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException invalidTime) {
            throw invalid("firstDeadline");
        }
    }
}
