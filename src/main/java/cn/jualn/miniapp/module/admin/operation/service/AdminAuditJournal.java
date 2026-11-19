package cn.jualn.miniapp.module.admin.operation.service;

import cn.jualn.miniapp.module.admin.operation.bo.AdminAuditBO.*;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminAuditMapper;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Durable acceptance in the owning transaction. No transport interception or request-body logging.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdminAuditJournal {
    private final AdminAuditMapper mapper;
    private final ObjectMapper json;

    public static LocalDateTime utcBusiness(LocalDateTime time) {
        return time.atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public static String wire(LocalDateTime time) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.atOffset(ZoneOffset.UTC));
    }

    public void operation(String event, long actor, String op, String type, String target, LocalDateTime utc) {
        if (op != null && (op.contains(".") || op.startsWith("createMy") || op.startsWith("submitMy"))) return;
        if (op == null || target == null)
            throw new IllegalArgumentException("Audit requires canonical operation and confirmed target");
        var attempt = AdminAuditAttemptContext.confirmed(actor, op, type, target);
        if (attempt != null) attempt.successEvent = event;
        Row row = row("O:" + event, "OPERATION", op, op, actor, type, target, utc);
        ObjectNode body = json.createObjectNode();
        body.put("eventId", row.getId());
        body.put("occurredAt", wire(utc));
        body.set("actor", actor(actor));
        body.put("operationId", op);
        body.put("targetType", type);
        body.put("targetId", target);
        body.put("result", "SUCCEEDED");
        body.set("relatedRecords", json.valueToTree(List.of(new Reference("AUDIT_EVENT", row.getId()))));
        append(row, body);
    }

    public void confirmed(long actor, String operation, String type, String target) {
        AdminAuditAttemptContext.confirmed(actor, operation, type, target);
    }

    public void attemptResult(String event, long actor, String operation, String type, String target, String result) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        Row row = row("O:" + event, "OPERATION", operation, operation, actor, type, target, now);
        row.setResult(result);
        var body = json.createObjectNode();
        body.put("eventId", row.getId());
        body.put("occurredAt", wire(now));
        body.set("actor", actor(actor));
        body.put("operationId", operation);
        body.put("targetType", type);
        body.put("targetId", target);
        body.put("result", result);
        body.set("relatedRecords", json.createArrayNode());
        append(row, body);
    }

    public void duty(String event, String group, long actor, String source, String action, String user, String person,
                     String recordKind, String recordId, JsonNode before, JsonNode after, LocalDateTime utc) {
        dutyWithReferences(event, group, actor, source, action, user, person, recordKind, recordId, before, after, utc, List.of());
    }

    public void dutyWithReferences(String event, String group, long actor, String source, String action, String user, String person,
                                   String recordKind, String recordId, JsonNode before, JsonNode after, LocalDateTime utc, List<Reference> dependencies) {
        Row row = row("D:" + event, "DUTY", source, source, actor, recordKind, recordId, utc);
        row.setTargetUser(user);
        row.setTargetPerson(person);
        ObjectNode body = json.createObjectNode();
        body.put("eventId", row.getId());
        body.put("changeGroupId", group);
        body.put("source", source);
        body.put("action", action);
        body.put("recordedAt", wire(utc));
        body.put("effectiveAt", wire(utc));
        body.set("actor", actor(actor));
        if (user != null) body.put("targetUserId", user);
        if (person != null) body.put("targetPersonId", person);
        body.put("reason", "Reason retained by source owner; free text redacted");
        var references = new ArrayList<Reference>();
        references.add(new Reference(recordKind, recordId));
        references.addAll(dependencies);
        body.set("relatedRecords", json.valueToTree(references.stream().distinct().toList()));
        body.set("before", state(before));
        body.set("after", state(after));
        append(row, body);
        if (Set.of("REVOKE", "INVALIDATE", "EXPIRE").contains(action))
            invalidateDependents(recordKind, recordId, row.getId(), utc);
    }

    public void personFact(String event, String group, long actor, String operation, String aggregate, String resource, String person,
                           String before, String after, LocalDateTime utc) {
        operation(event, actor, operation, aggregate, resource, utc);
        String source = "BINDING".equals(aggregate) ? "BINDING" : "SCOPE".equals(aggregate) ? "SCOPE" :
                "MENTOR".equals(aggregate) ? "QUALIFICATION" : "PERSON".equals(aggregate) ?
                        (operation.toLowerCase(java.util.Locale.ROOT).contains("member") ? "MEMBERSHIP" : "QUALIFICATION") : null;
        if (source == null || "MENTOR".equals(aggregate) && !"changeMentorQualification".equals(operation)) return;
        String kind = switch (source) {
            case "BINDING" -> "PERSON_BINDING";
            case "SCOPE" -> "SCOPE_GRANT";
            case "MEMBERSHIP" -> "MEMBERSHIP_CHANGE";
            default -> "QUALIFICATION_CHANGE";
        };
        JsonNode b = decode(before), a = decode(after);
        if ("QUALIFICATION".equals(source)) {
            if (b.hasNonNull("currentRecord")) b = b.get("currentRecord");
            if (a.hasNonNull("currentRecord")) a = a.get("currentRecord");
        }
        String user = text(a, "granteeUserId");
        if (user == null) user = text(a, "userId");
        String factId = resource;
        if ("QUALIFICATION".equals(source)) for (String field : List.of("qualificationId", "recordId")) {
            if (a.hasNonNull(field)) {
                factId = a.get(field).asText();
                break;
            }
            if (b.hasNonNull(field)) {
                factId = b.get(field).asText();
                break;
            }
        }
        duty(event, group == null ? event : group, actor, source, b.isNull() ? "GRANT" : a.path("status").asText().equals("REVOKED") ? "REVOKE" : "CHANGE", user, person, kind, factId, b, a, utc);
        if ("MEMBERSHIP".equals(source)) {
            var ended = new HashSet<>(b.findValuesAsText("membershipId"));
            ended.removeAll(a.findValuesAsText("membershipId"));
            for (String membership : ended) invalidateDependents("MEMBERSHIP_CHANGE", membership, "D:" + event, utc);
            var previous = membershipFacts(b);
            var current = membershipFacts(a);
            for (var entry : current.entrySet())
                if (!previous.containsKey(entry.getKey())) {
                    var member = entry.getValue();
                    var state = json.createObjectNode();
                    state.put("status", "ACTIVE");
                    state.put("membershipId", entry.getKey());
                    if (member.hasNonNull("validFrom")) state.set("validFrom", member.get("validFrom"));
                    if (member.has("validUntil")) state.set("expiresAt", member.get("validUntil"));
                    duty(event + ":" + entry.getKey(), group == null ? event : group, actor, "MEMBERSHIP", "GRANT", null, person, "MEMBERSHIP_CHANGE", entry.getKey(), null, state, utc);
                }
            for (var entry : previous.entrySet())
                if (!current.containsKey(entry.getKey())) {
                    var prior = json.createObjectNode();
                    prior.put("status", "ACTIVE");
                    prior.put("membershipId", entry.getKey());
                    var next = prior.deepCopy();
                    next.put("status", "ENDED");
                    duty(event + ":" + entry.getKey(), group == null ? event : group, actor, "MEMBERSHIP", "REVOKE", null, person, "MEMBERSHIP_CHANGE", entry.getKey(), prior, next, utc);
                }
        }
    }

    private Map<String, JsonNode> membershipFacts(JsonNode value) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (var member : value.path("currentMemberships"))
            if (member.hasNonNull("membershipId")) result.put(member.get("membershipId").asText(), member);
        return result;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    public void delegation(UnifiedModels.AuthorityRow authority,
                           List<UnifiedModels.Selection> ceilings,
                           List<UnifiedModels.Binding> bindings,
                           long actor, String event, String action, LocalDateTime at) {
        var state = json.createObjectNode();
        state.put("status", "ACTIVE");
        state.put("delegationAuthorityId", authority.getId());
        state.put("validFrom", wire(authority.getGrantedAt()));
        state.put("expiresAt", wire(authority.getExpiresAt()));
        state.set("targets", tree(ceilings.stream().map(c -> c.target()).distinct().toList()));
        var refs = new ArrayList<Reference>();
        refs.add(new Reference("ROLE_ASSIGNMENT", authority.getRootAssignmentId()));
        String person = null;
        for (var b : bindings) {
            refs.add(new Reference("PERSON_BINDING", b.bindingId()));
            refs.add(new Reference("MEMBERSHIP_CHANGE", b.membershipId()));
            if (b.userId() == authority.getGranteeUserId()) person = b.personId();
        }
        var after = state.deepCopy();
        if (!"GRANT".equals(action)) after.put("status", "REVOKED");
        dutyWithReferences(event, event, actor, "DELEGATION", action, Long.toString(authority.getGranteeUserId()), person, "DELEGATION_AUTHORITY", authority.getId(),
                "GRANT".equals(action) ? null : state, after, at, refs);
    }

    public void unifiedScope(PersonBusinessScopes.Scope scope,
                             UnifiedModels.Selection selection,
                             List<UnifiedModels.Binding> bindings,
                             long actor, String group, String action, LocalDateTime at) {
        var state = json.createObjectNode();
        state.put("status", "ACTIVE");
        state.put("dutyCode", scope.dutyCode());
        state.set("targets", tree(List.of(selection.target())));
        if (selection.audienceTargets() != null) state.set("audienceTargets", tree(selection.audienceTargets()));
        state.put("validFrom", wire(scope.grantedAt()));
        if (scope.expiresAt() == null) state.putNull("expiresAt");
        else state.put("expiresAt", wire(scope.expiresAt()));
        state.set("assignmentIds", tree(List.of(scope.assignmentId())));
        state.set("scopeGrantIds", tree(List.of(scope.id())));
        if (scope.authorityId() != null) state.put("delegationAuthorityId", scope.authorityId());
        var refs = new ArrayList<Reference>();
        refs.add(new Reference("ROLE_ASSIGNMENT", scope.assignmentId()));
        refs.add(new Reference("DUTY_CHANGE", scope.changeId()));
        if (scope.authorityId() != null) refs.add(new Reference("DELEGATION_AUTHORITY", scope.authorityId()));
        if (scope.rootAssignmentId() != null) refs.add(new Reference("ROLE_ASSIGNMENT", scope.rootAssignmentId()));
        String person = null;
        for (var b : bindings) {
            refs.add(new Reference("PERSON_BINDING", b.bindingId()));
            refs.add(new Reference("MEMBERSHIP_CHANGE", b.membershipId()));
            if (b.userId() == scope.granteeUserId()) {
                person = b.personId();
                state.put("bindingId", b.bindingId());
                state.put("membershipId", b.membershipId());
            }
        }
        ObjectNode changed = state.deepCopy();
        if (!"GRANT".equals(action)) changed.put("status", "REVOKE".equals(action) ? "REVOKED" : "SOURCE_INVALIDATED");
        for (String source : List.of("DUTY", "SCOPE")) {
            JsonNode before = null;
            if (!"GRANT".equals(action)) {
                var prior = mapper.latestDuty(source, scope.id());
                before = prior == null ? state : decode(prior.getPayload()).path("after");
                if ("INVALIDATE".equals(action) && !"ACTIVE".equals(before.path("status").asText())) continue;
            }
            dutyWithReferences(group + ":" + source + ":" + scope.id(), group, actor, source, action,
                    Long.toString(scope.granteeUserId()), person, "SCOPE_GRANT", scope.id(), before, changed, at, refs);
        }
    }

    public void expireRecordedFacts(LocalDateTime now) {
        if (mapper.lock() == null) throw new IllegalStateException("Missing audit guard");
        for (var row : mapper.expiryCandidates(now)) {
            var original = decode(row.getPayload());
            var value = original.path("after").get("expiresAt");
            if (value == null || value.isNull()) continue;
            var expiry = java.time.OffsetDateTime.parse(value.asText()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if (expiry.isAfter(now)) continue;
            var primary = original.path("relatedRecords").get(0);
            if (primary == null) throw new IllegalStateException("Missing audit reference");
            transition(row, original, "EXPIRE", expiry, "expiry:" + row.getId(), now, null);
        }
    }

    private void invalidateDependents(String kind, String id, String cause, LocalDateTime now) {
        for (var row : mapper.dependent(kind, id)) {
            var original = decode(row.getPayload());
            // A dependent source can be invalidated once. Later rebinding cannot revive this source.
            transition(row, original, "INVALIDATE", now, "source:" + row.getId(), now, cause);
        }
    }

    private void transition(Row row, JsonNode original, String action, LocalDateTime effective, String identity, LocalDateTime recorded, String cause) {
        String event = "D:" + identity;
        if (mapper.exists(event) > 0 || mapper.exists("D:source:" + row.getId()) > 0 || mapper.exists("D:expiry:" + row.getId()) > 0)
            return;
        var primary = original.path("relatedRecords").get(0);
        if (primary == null) throw new IllegalStateException("Missing audit reference");
        var state = original.path("after").deepCopy();
        ((ObjectNode) state).put("status", "EXPIRE".equals(action) ? "EXPIRED" : "SOURCE_INVALIDATED");
        Row next = row(event, "DUTY", row.getSource(), row.getOperation(), 0, row.getTargetType(), row.getTargetId(), recorded);
        next.setTargetUser(row.getTargetUser());
        next.setTargetPerson(row.getTargetPerson());
        ObjectNode body = ((ObjectNode) original).deepCopy();
        body.put("eventId", event);
        body.put("action", action);
        body.put("recordedAt", wire(recorded));
        body.put("effectiveAt", wire(effective));
        body.set("actor", actor(0));
        body.set("before", original.path("after"));
        body.set("after", state);
        body.put("reason", "EXPIRE".equals(action) ? "Recorded source reached its fixed expiry" : "Recorded dependency ended; original source is not restored");
        if (cause != null) ((ArrayNode) body.get("relatedRecords")).add(tree(new Reference("AUDIT_EVENT", cause)));
        append(next, body);
        invalidateDependents(primary.path("kind").asText(), primary.path("id").asText(), next.getId(), recorded);
    }

    /**
     * Closed allowlist; identifiers/codes originate from the owner's typed facts, never a raw command.
     */
    private JsonNode state(JsonNode input) {
        if (input == null || input.isNull()) return NullNode.instance;
        var output = json.createObjectNode();
        for (String key : List.of("status", "dutyCode", "qualificationCode", "roleCode", "targets", "audienceTargets", "validFrom", "expiresAt", "assignmentIds", "scopeGrantIds", "delegationAuthorityId", "bindingId", "membershipId"))
            if (input.has(key)) {
                var value = input.get(key);
                if ("validFrom".equals(key) && value.isNull()) continue;
                output.set(key, value);
            }
        if (!output.hasNonNull("status")) {
            if (input.hasNonNull("qualificationStatus")) output.set("status", input.get("qualificationStatus"));
            else if (input.hasNonNull("grantedAt"))
                output.put("status", input.hasNonNull("revokedAt") ? "REVOKED" : "ACTIVE");
            else if (input.hasNonNull("presence")) output.set("status", input.get("presence"));
            else output.put("status", "RECORDED");
        }
        if (input.has("validUntil") && !output.has("expiresAt")) output.set("expiresAt", input.get("validUntil"));
        if (input.hasNonNull("target")) output.set("targets", tree(List.of(input.get("target"))));
        if (input.hasNonNull("scopeGrantId"))
            output.set("scopeGrantIds", tree(List.of(input.get("scopeGrantId").asText())));
        if (input.hasNonNull("currentMemberships")) {
            var targets = json.createArrayNode();
            for (var membership : input.path("currentMemberships")) {
                var target = json.createObjectNode();
                target.put("type", "ORGANIZATION");
                target.set("organizationId", membership.get("organizationId"));
                targets.add(target);
            }
            output.set("targets", targets);
            output.put("status", targets.isEmpty() ? "NONE" : "RECORDED");
        }
        if (input.hasNonNull("stageId")) output.set("qualificationCode", input.get("stageId"));
        if (input.hasNonNull("qualificationStatus")) output.put("qualificationCode", "MENTOR");
        for (String field : List.of("validFrom", "expiresAt"))
            if (output.hasNonNull(field)) output.put(field, utcFactTime(output.get(field).asText()));
        return output;
    }

    private String utcFactTime(String value) {
        try {
            return wire(OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        } catch (DateTimeParseException noOffset) {
            try {
                return wire(LocalDateTime.parse(value));
            } catch (DateTimeParseException legacy) {
                var format = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss")
                        .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter();
                return wire(LocalDateTime.parse(value, format));
            }
        }
    }

    private static final ObjectMapper FACTS = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JsonNode tree(Object value) {
        return value == null ? NullNode.instance : FACTS.valueToTree(value);
    }

    public JsonNode decode(String value) {
        if (value == null) return NullNode.instance;
        try {
            return json.readTree(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid owner audit fact", e);
        }
    }

    private ObjectNode actor(long user) {
        var n = json.createObjectNode();
        n.put("type", user == 0 ? "SYSTEM" : "USER");
        if (user != 0) n.put("userId", Long.toString(user));
        return n;
    }

    private Row row(String id, String kind, String source, String op, long actor, String type, String target, LocalDateTime utc) {
        Row r = new Row();
        r.setId(id);
        r.setKind(kind);
        r.setSource(source);
        r.setOperation(op);
        r.setActorUser(actor == 0 ? null : Long.toString(actor));
        r.setTargetType(type);
        r.setTargetId(target);
        r.setRecordedAt(utc);
        r.setResult("SUCCEEDED");
        return r;
    }

    private void append(Row row, JsonNode body) {
        if (mapper.lock() == null) throw new IllegalStateException("Missing audit guard");
        row.setPayload(body.toString());
        if (mapper.insert(row) != 1) throw new IllegalStateException("Audit acceptance failed");
    }
}
