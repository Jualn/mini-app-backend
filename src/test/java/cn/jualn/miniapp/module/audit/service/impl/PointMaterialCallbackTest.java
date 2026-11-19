package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.config.AuditConfig;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.media.service.PointMaterialAuditCallback;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.third.wx.client.WxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PointMaterialCallbackTest {
    final ContentAuditLogMapper logs = mock(ContentAuditLogMapper.class);
    final AuditResultPersistenceService persistence = mock(AuditResultPersistenceService.class);
    final AuditServiceImpl audit = new AuditServiceImpl(mock(WxClient.class), logs, mock(RedisService.class), new ObjectMapper(), persistence);

    WxaMediaCheckMessage callback(String suggest, Integer error) {
        when(logs.selectOne(any())).thenReturn(ContentAuditLog.builder().id(3L).targetId(7L).targetType(12).build());
        var message = new WxaMediaCheckMessage();
        message.setTraceId("synthetic-points-trace");
        message.setErrCode(error);
        if (suggest != null) {
            var result = new WxaMediaCheckMessage.Result(); result.setSuggest(suggest); message.setResult(result);
        }
        return message;
    }

    @Test void incompleteOrProviderErrorNeverApprovesAnImage() {
        for (String suggest : new String[]{null, "unknown", "review"}) audit.handleWxMediaCallback(callback(suggest, 0));
        audit.handleWxMediaCallback(callback("pass", 1));
        audit.handleWxMediaCallback(callback("pass", null));
        var inconsistent = callback("pass", 0);
        var detail = new WxaMediaCheckMessage.Detail(); detail.setErrCode(0); detail.setSuggest("risky");
        inconsistent.setDetail(List.of(detail)); audit.handleWxMediaCallback(inconsistent);
        verifyNoInteractions(persistence);
    }

    @Test void explicitPassAndRejectionUseThePrivatePointsScene() {
        audit.handleWxMediaCallback(callback("pass", 0));
        verify(persistence).completeMedia(eq(3L), eq(AuditScene.POINTS_MATERIAL), eq(7L), eq("synthetic-points-trace"), eq(AuditStatus.PASS), anyString(), isNull());
        audit.handleWxMediaCallback(callback("risky", 0));
        verify(persistence).completeMedia(eq(3L), eq(AuditScene.POINTS_MATERIAL), eq(7L), eq("synthetic-points-trace"), eq(AuditStatus.REJECT), anyString(), anyString());
        var callback = new PointMaterialAuditCallback();
        assertSame(callback, new AuditConfig().callbackRegister(List.of(callback)).get(AuditScene.POINTS_MATERIAL));
    }
}
