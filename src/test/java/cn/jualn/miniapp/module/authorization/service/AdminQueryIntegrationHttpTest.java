package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full local HTTP/authentication/owner/MySQL evidence. Provider doubles and synthetic publication are inherited explicitly. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="points.integration.jdbcUrl",
        matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_integration_test_[a-z0-9]+")
class AdminQueryIntegrationHttpTest extends PointsIntegrationHttpTest {
    private final QueryCounts counts = new QueryCounts();
    private final Path evidence = Path.of("target/query-optimization");

    @Override @BeforeAll void start() throws Exception {
        System.setProperty("unified.r3.fixture", "EXPLICIT_SYNTHETIC_R3_APPROVAL");
        super.start();
        Files.createDirectories(evidence);
        app.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(counts);
    }

    @Override @AfterAll void stop() throws Exception {
        try {
            super.stop();
        } finally {
            System.clearProperty("unified.r3.fixture");
        }
    }

    @Test void queryDutyAndNameBoundaries() throws Exception {
        long actor = 930120;
        syntheticUser(actor, true);
        var grant = duty(actor, "USER_ACCOUNT_GOVERN", new UnifiedModels.Target("PLATFORM", null, null));
        String token = qr(actor);
        String summary = AUTH + "/me/duty-summary?dutyCode=USER_ACCOUNT_GOVERN&dutyCode=LEARNING_REVIEW";
        var value = expect(200, "GET", summary, token, null);
        assertEquals(Long.toString(actor), value.path("userId").asText());
        assertTrue(value.path("items").get(0).path("effective").asBoolean());
        assertFalse(value.path("items").get(1).path("effective").asBoolean());
        assertTrue(Instant.parse(value.path("refreshAfter").asText()).isAfter(Instant.parse(value.path("evaluatedAt").asText())));
        assertFalse(Instant.parse(value.path("refreshAfter").asText()).isAfter(Instant.parse(value.path("evaluatedAt").asText()).plusSeconds(60)));
        assertFalse(expect(200, "GET", summary, root, null).path("items").get(0).path("effective").asBoolean());
        for (String query : List.of("", "?dutyCode=UNKNOWN", "?dutyCode=LEARNING_REVIEW&dutyCode=LEARNING_REVIEW", "?dutyCode=", "?dutyCode=LEARNING_REVIEW&userId=1")) {
            checkedNoStore(400, call("GET", AUTH + "/me/duty-summary" + query, token, null, Map.of()));
        }
        db.update("UPDATE user_profile SET nickname='   ' WHERE id=?", USER);
        var names = expect(200, "GET", "/v1/admin/users/names?userId=" + USER + "&userId=opaque-missing&userId=" + actor, token, null);
        assertTrue(names.path("items").get(0).has("name"));
        assertTrue(names.path("items").get(0).path("name").isNull());
        assertEquals("UNAVAILABLE", names.path("items").get(1).path("status").asText());
        assertFalse(names.path("items").get(1).has("name"));
        assertFalse(names.toString().contains("openid"));
        checkedNoStore(403, call("GET", "/v1/admin/users/names?userId=" + USER, reviewer, null, Map.of()));
        checkedNoStore(400, call("GET", "/v1/admin/users/names?userId=1&userId=1", token, null, Map.of()));
        revokeDuty(grant.scopeGrantIds().get(0));
        assertFalse(expect(200, "GET", summary, token, null).path("items").get(0).path("effective").asBoolean());
        checkedNoStore(403, call("GET", "/v1/admin/users/names?userId=" + USER, token, null, Map.of()));

        long memberReader = 930121;
        syntheticUser(memberReader, true);
        String allowed = organization("Name allowed"), hidden = organization("Name hidden");
        duty(memberReader, "ORG_MEMBER_READ", new UnifiedModels.Target("ORGANIZATION", null, allowed));
        String scopedToken = qr(memberReader);
        var organizations = expect(200, "GET", "/v1/admin/organizations/names?organizationId=" + allowed + "&organizationId=" + hidden + "&organizationId=missing", scopedToken, null);
        assertEquals("READY", organizations.path("items").get(0).path("status").asText());
        assertEquals("Name allowed", organizations.path("items").get(0).path("name").asText());
        assertEquals("UNAVAILABLE", organizations.path("items").get(1).path("status").asText());
        assertEquals("UNAVAILABLE", organizations.path("items").get(2).path("status").asText());
        checkedNoStore(403, call("GET", "/v1/admin/organizations/names?organizationId=" + allowed, token, null, Map.of()));
    }

    @Test void queryDutyUnknownAndKnownRefreshBoundary() throws Exception {
        long actor = 930122;
        syntheticUser(actor, true);
        var rootAssignment = app.getBean(RbacAuthorizationReader.class).read(ROOT).grants().stream().filter(g -> "ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
        var expiry = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(45).withNano(0);
        var selection = new UnifiedModels.Selection("USER_ACCOUNT_GOVERN", new UnifiedModels.Target("PLATFORM", null, null), RbacTime.wire(expiry), null);
        var intent = new UnifiedModels.Intent("GRANT", Long.toString(actor), List.of(selection), rootAssignment, null, null, "Isolated short source");
        var service = app.getBean(UnifiedAuthorizationService.class);
        var preview = service.preview(ROOT, intent, UUID.randomUUID().toString());
        var grant = service.commit(ROOT, new UnifiedModels.Commit(preview.previewId(), intent), UUID.randomUUID().toString());
        String token = qr(actor), path = AUTH + "/me/duty-summary?dutyCode=USER_ACCOUNT_GOVERN";
        var value = expect(200, "GET", path, token, null);
        assertEquals(RbacTime.wire(expiry), value.path("refreshAfter").asText());
        String change = db.queryForObject("SELECT change_id FROM person_business_scope WHERE id=?", String.class, grant.scopeGrantIds().get(0));
        String original = db.queryForObject("SELECT audit_json FROM rbac_unified_command WHERE id=?", String.class, change);
        db.update("UPDATE rbac_unified_command SET audit_json='[]' WHERE id=?", change);
        try {
            checkedNoStore(503, call("GET", path, token, null, Map.of()));
        } finally {
            db.update("UPDATE rbac_unified_command SET audit_json=? WHERE id=?", original, change);
        }
    }

    @Test void queryAttachmentBatchAndCommittedParent() throws Exception {
        long actor = 930123;
        syntheticUser(actor, true);
        duty(actor, "ACTIVITY_MANAGE", new UnifiedModels.Target("PLATFORM", null, null));
        String token = qr(actor);
        for (long id : List.of(991101L, 991102L)) {
            db.update("INSERT INTO media_attachment(id,type,kind,registered,registered_by,object_key,url,original_name,sort_order) VALUES(?,2,'PDF',1,? ,? ,? ,'query fixture',0)", id, actor, "query/" + id, "https://example.org/" + id);
            db.update("INSERT INTO media_upload_record(object_key,user_id,target_type,status,cleanup_after,bound_attachment_id) VALUES(?,?,2,1,DATE_ADD(NOW(),INTERVAL 1 DAY),?)", "query/" + id, actor, id);
        }
        String path = "/v1/admin/attachments/batch?attachmentId=991102&attachmentId=missing&attachmentId=991101";
        var items = expect(200, "GET", path, token, null).path("items");
        assertEquals(3, items.size());
        assertEquals("991102", items.get(0).path("attachment").path("attachmentId").asText());
        assertEquals("UNAVAILABLE", items.get(1).path("status").asText());
        checkedNoStore(400, call("GET", "/v1/admin/attachments/batch?attachmentId=991101&attachmentId=991101", token, null, Map.of()));
        assertEquals("UNAVAILABLE", expect(200, "GET", "/v1/admin/attachments/batch?attachmentId=991101", reviewer, null).path("items").get(0).path("status").asText());

        var draft = Map.of("title", "Query projection save", "registrationMode", "NONE", "timeline", List.of(), "sections", List.of(), "actions", List.of(), "contacts", List.of(),
                "attachments", List.of(Map.of("attachmentId", "991102", "displayOrder", 2), Map.of("attachmentId", "991101", "displayOrder", 1)));
        var created = call("POST", "/v1/admin/activities", token, draft, Map.of());
        var body = checked(201, created);
        assertEquals("991101", body.path("attachmentDetails").path("items").get(0).path("attachmentId").asText());
        var parent = "/v1/admin/activities/" + body.path("activityId").asText();
        String tag = created.headers().firstValue("ETag").orElseThrow();
        // Inject a read failure only after the parent transaction has committed; no production failpoint.
        counts.failProjection.set(true);
        HttpResponse<String> saved;
        try {
            saved = call("PUT", parent, token, draft, Map.of("If-Match", tag));
        } finally {
            counts.failProjection.set(false);
        }
        var savedBody = checked(200, saved);
        assertEquals("ERROR", savedBody.path("attachmentDetails").path("items").get(0).path("status").asText());
        assertEquals(2, savedBody.path("draft").path("attachments").size());
        assertTrue(saved.headers().firstValue("ETag").isPresent());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM event_attachment_link WHERE target_type=2 AND target_id=?", Integer.class, Long.parseLong(body.path("activityId").asText())));

        duty(actor, "PUBLIC_EVENT_MANAGE", new UnifiedModels.Target("PLATFORM", null, null));
        var eventDraft = Map.of("title", "Query public event", "type", "OTHER", "timeline", List.of(), "sections", List.of(),
                "actions", List.of(), "contacts", List.of(), "attachments", draft.get("attachments"));
        var event = call("POST", "/v1/admin/public-events", token, eventDraft, Map.of());
        var eventBody = checked(201, event);
        assertEquals(2, eventBody.path("attachmentDetails").path("items").size());
        var eventPath = "/v1/admin/public-events/" + eventBody.path("publicEventId").asText();
        var eventTag = event.headers().firstValue("ETag").orElseThrow();
        assertEquals(eventBody, expect(200, "GET", eventPath, token, null));
        var replaced = call("PUT", eventPath, token, eventDraft, Map.of("If-Match", eventTag));
        assertEquals(2, checked(200, replaced).path("attachmentDetails").path("items").size());

        var measurements = new ArrayList<Map<String, Object>>();
        for (var scenario : List.of(List.of("/v1/admin/attachments/991101", "/v1/admin/attachments/991102"), List.of("/v1/admin/attachments/batch?attachmentId=991101&attachmentId=991102"))) {
            counts.value.set(0);
            long start = System.nanoTime(), bytes = 0;
            for (var request : scenario) {
                var response = call("GET", request, token, null, Map.of());
                checked(200, response);
                bytes += response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            measurements.add(Map.of("paths", scenario, "requests", scenario.size(), "responseBytes", bytes,
                    "mapperQueries", counts.value.get(), "elapsedNanos", System.nanoTime() - start));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("attachment-comparison.json").toFile(), measurements);
        assertTrue((int) measurements.get(1).get("mapperQueries") < (int) measurements.get(0).get("mapperQueries"));
    }


    private void checkedNoStore(int status, HttpResponse<String> response) throws Exception {
        checked(status, response);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class QueryCounts implements Interceptor {
        final AtomicInteger value = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failProjection = new java.util.concurrent.atomic.AtomicBoolean();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
                if (request.getRequest().getRequestURI().startsWith("/v1/admin/attachments/")) {
                    value.incrementAndGet();
                }
                if (failProjection.get() && "PUT".equals(request.getRequest().getMethod())
                        && request.getRequest().getRequestURI().startsWith("/v1/admin/activities/")
                        && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".selectBatchAccess")) {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    throw new org.springframework.dao.DataAccessResourceFailureException("Isolated post-commit expansion read failure");
                }
            }
            return invocation.proceed();
        }
    }
}
