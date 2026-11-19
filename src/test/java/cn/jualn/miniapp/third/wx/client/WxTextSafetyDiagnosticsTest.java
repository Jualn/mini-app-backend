package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.module.audit.bo.AuditTextCheckBO;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.ContentSafetyResults;
import cn.jualn.miniapp.module.audit.service.impl.AuditResultPersistenceService;
import cn.jualn.miniapp.module.audit.service.impl.AuditServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WxTextSafetyDiagnosticsTest {
    @Test void actualClientParsingPreservesAbsentKeywordAndExplicitModelDecision() {
        var client = client("""
            {"errcode":0,"errmsg":"ok","detail":[{"strategy":"keyword","errcode":0},
             {"strategy":"content_model","errcode":0,"suggest":"pass","label":100,"prob":90}],
             "result":{"suggest":"pass","label":100},"trace_id":"synthetic"}
            """);
        var response = client.msgSecCheck(cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest.builder()
                .openid("synthetic").content("synthetic").version(2).scene(1).build());
        assertEquals("pass", response.getResultSuggest());
        assertEquals("keyword", response.getDetail().get(0).getStrategy());
        assertNull(response.getDetail().get(0).getSuggest());
        assertEquals("pass", response.getDetail().get(1).getSuggest());
        var users = org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);
        org.mockito.Mockito.when(users.getMiniOpenid(2L)).thenReturn("synthetic");
        var safety = new cn.jualn.miniapp.module.audit.service.LearningTaskTextSafetyCheckService(client, users);
        assertEquals(503, assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> safety.check(2L, "synthetic")).getStatus().value());
        var testEnv = new org.springframework.mock.env.MockEnvironment();
        testEnv.setActiveProfiles("dev", "learning-task-integration");
        assertDoesNotThrow(() -> new cn.jualn.miniapp.module.audit.service.LearningTaskTextSafetyCheckService(
                client, users, testEnv).check(2L, "synthetic"));
        testEnv.setActiveProfiles("dev", "learning-task-integration", "prod");
        assertEquals(503, assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> new cn.jualn.miniapp.module.audit.service.LearningTaskTextSafetyCheckService(
                        client, users, testEnv).check(2L, "synthetic")).getStatus().value());
    }

    @Test void malformedProviderFieldsStillBecomeDependencyFailureWithoutSensitiveCause() {
        var client = client("{\"errcode\":0,\"detail\":\"secret\"}");
        var failure = assertThrows(cn.jualn.miniapp.common.exception.ExternalServiceException.class,
                () -> client.msgSecCheck(cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest.builder().build()));
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
    }

    @Test void postAcceptsVerifiedKeywordNoHitAndBlocksUnverifiedResponses() {
        String response = """
                {"errcode":0,"errmsg":"ok","trace_id":"synthetic",
                 "result":{"suggest":"pass","label":100},"detail":[
                  {"strategy":"keyword","errcode":0},
                  {"strategy":"content_model","errcode":0,"suggest":"pass","label":100,"prob":90}]}
                """;
        var users = org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class);
        org.mockito.Mockito.when(users.getMiniOpenid(2L)).thenReturn("synthetic");
        var verified = new cn.jualn.miniapp.module.audit.service.PostSafetyCheckService(client(response), users, null, null);
        assertDoesNotThrow(() -> verified.check(2L, 0L, "synthetic", java.util.List.of()));

        for (String invalid : java.util.List.of(
                response.replace("\"strategy\":\"keyword\",\"errcode\":0", "\"strategy\":\"keyword\",\"errcode\":0,\"suggest\":null"),
                response.replace("\"strategy\":\"content_model\"", "\"strategy\":\"unknown\""))) {
            var safety = new cn.jualn.miniapp.module.audit.service.PostSafetyCheckService(client(invalid), users, null, null);
            var problem = assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                    () -> safety.check(2L, 0L, "synthetic", java.util.List.of()));
            assertEquals(503, problem.getStatus().value());
        }
    }

    @Test void duplicateKeysAndTrailingDocumentsNeverBecomePass() {
        for (String body : new String[]{"{\"errcode\":1,\"errcode\":0}", "{} {}", "  "}) {
            assertThrows(cn.jualn.miniapp.common.exception.ExternalServiceException.class,
                    () -> client(body).msgSecCheck(cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest.builder().build()));
        }
    }

    @Test void commentWorkerPersistsVerifiedKeywordNoHitAsPass() {
        String response = """
                {"errcode":0,"errmsg":"ok","trace_id":"synthetic",
                 "result":{"suggest":"pass","label":100},"detail":[
                  {"strategy":"keyword","errcode":0},
                  {"strategy":"content_model","errcode":0,"suggest":"pass","label":100,"prob":90}]}
                """;
        assertEquals("pass", commentObservation(response).result());
        assertEquals("unknown", commentObservation(response.replace("\"strategy\":\"keyword\",\"errcode\":0",
                "\"strategy\":\"keyword\",\"errcode\":0,\"suggest\":null")).result());
        assertEquals("unknown", commentObservation(response.replace("\"strategy\":\"content_model\"",
                "\"strategy\":\"unknown\"")).result());
    }

    private ContentSafetyResults.Observation commentObservation(String response) {
        var persistence = mock(AuditResultPersistenceService.class);
        var json = new ObjectMapper();
        var audit = new AuditServiceImpl(client(response), mock(ContentAuditLogMapper.class), null, json, persistence);
        audit.processTextAudit(AuditTextCheckBO.builder().auditLogId(1L).auditScene(AuditScene.COMMENT)
                .targetId(2L).openid("synthetic").content("synthetic").build());
        var facts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(persistence).completeText(eq(1L), eq(AuditScene.COMMENT), eq(2L), eq("synthetic"),
                eq(AuditStatus.PASS), facts.capture(), isNull());
        return ContentSafetyResults.interpret(json, facts.getValue());
    }

    private WxClient client(String response) {
        var web = org.springframework.web.reactive.function.client.WebClient.builder()
                .exchangeFunction(request -> reactor.core.publisher.Mono.just(
                        org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                                .header("Content-Type", "application/json").body(response).build())).build();
        var redis = org.mockito.Mockito.mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class);
        org.mockito.Mockito.when(redis.getString(cn.jualn.miniapp.common.constant.RedisKeyConstant.wxAccessToken("ma", "synthetic")))
                .thenReturn("synthetic-token");
        return new WxClient(web, new cn.jualn.miniapp.third.wx.config.WxProperties(null,
                new cn.jualn.miniapp.third.wx.config.WxProperties.Ma("synthetic", "synthetic", null, null)),
                redis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test void distinguishesAbsentNullAndTypesWithoutLeakingUntrustedFields() throws Exception {
        var body = new ObjectMapper().readTree("""
            {"errcode":0,"errmsg":"secret","openid":"secret","trace_id":"secret",
             "result":{"suggest":"pass","label":100},"detail":[
              {"strategy":"keyword","errcode":0,"keyword":"secret"},
              {"strategy":"content_model","errcode":0,"suggest":null},
              {"strategy":"secret","suggest":"secret\\nforged","label":"secret"}]}
            """);
        String summary = WxTextSafetyDiagnostics.summary(body);
        assertTrue(summary.contains("suggest=STRING:pass"));
        assertTrue(summary.contains("suggest=MISSING"));
        assertTrue(summary.contains("suggest=NULL"));
        assertTrue(summary.contains("strategy=STRING:keyword"));
        assertFalse(summary.contains("secret"));
        assertFalse(summary.contains("forged"));
        assertFalse(summary.contains("\n"));
    }

    @Test void limitsDetailOutput() throws Exception {
        var body = new ObjectMapper().createObjectNode();
        var detail = body.putArray("detail");
        for (int i = 0; i < 100; i++) detail.addObject().put("errcode", 0);
        String summary = WxTextSafetyDiagnostics.summary(body);
        assertTrue(summary.contains("count=100"));
        assertTrue(summary.contains("item[15]"));
        assertFalse(summary.contains("item[16]"));
    }
}
