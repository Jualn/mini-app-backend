package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfileMediaCallbackTest {
    private final ContentAuditLogMapper logs = mock(ContentAuditLogMapper.class);
    private final AuditResultPersistenceService persistence = mock(AuditResultPersistenceService.class);
    private final AuditServiceImpl audit = new AuditServiceImpl(mock(WxClient.class), logs, mock(RedisService.class), new ObjectMapper(), persistence);
    private WxaMediaCheckMessage callback(String suggest, Integer error) {
        when(logs.selectOne(any())).thenReturn(ContentAuditLog.builder().id(3L).targetId(7L).targetType(AuditScene.USER_AVATAR.getCode()).build());
        var message = new WxaMediaCheckMessage(); message.setTraceId("synthetic-trace"); message.setErrCode(error);
        if (suggest != null) { var result = new WxaMediaCheckMessage.Result(); result.setSuggest(suggest); message.setResult(result); }
        return message;
    }
    @Test void missingUnknownReviewOrProviderErrorAreNotDefinitiveDecisions() {
        for (String suggest : new String[]{null, "unknown", "review"}) audit.handleWxMediaCallback(callback(suggest, 0));
        audit.handleWxMediaCallback(callback("pass", 1)); audit.handleWxMediaCallback(callback("pass", null));
        verifyNoInteractions(persistence);
    }
    @Test void authoritativeResultUsesExistingAuditPersistenceAndOutboxBoundary() {
        audit.handleWxMediaCallback(callback("pass", 0));
        verify(persistence).completeMedia(eq(3L), eq(AuditScene.USER_AVATAR), eq(7L), eq("synthetic-trace"), eq(AuditStatus.PASS), anyString(), isNull());
        audit.handleWxMediaCallback(callback("risky", 0));
        verify(persistence).completeMedia(eq(3L), eq(AuditScene.USER_AVATAR), eq(7L), eq("synthetic-trace"), eq(AuditStatus.REJECT), anyString(), anyString());
    }
    @Test void partialDetailPassDoesNotStandInForAnOverallDecision() {
        var message = callback(null, 0); var detail = new WxaMediaCheckMessage.Detail();
        detail.setErrCode(0); detail.setSuggest("pass"); message.setDetail(java.util.List.of(detail));
        audit.handleWxMediaCallback(message); verifyNoInteractions(persistence);
    }
}
