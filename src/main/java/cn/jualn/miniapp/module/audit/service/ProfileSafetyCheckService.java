package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.List;

/** Checks candidates only. No job or callback owns permission to publish a profile. */
@Service
@RequiredArgsConstructor
public class ProfileSafetyCheckService {
    private final WxClient wxClient;
    private final AuditReservationService reservations;
    private final ContentAuditLogMapper logs;

    @Value("${app.user-profile.media-check-wait-millis:10000}")
    private long mediaCheckWaitMillis = 10000;

    @Transactional(propagation = Propagation.NEVER)
    public void checkText(String openid, String content) {
        if (content == null || content.isEmpty()) return;
        try {
            var response = wxClient.msgSecCheck(WxMsgSecCheckRequest.builder()
                    .openid(openid).content(content).version(2).scene(1).build());
            String suggest = response == null ? null : response.getResultSuggest();
            if ("pass".equals(suggest)) return;
            if ("risky".equals(suggest) || "reject".equals(suggest)) throw rejected();
            throw unavailable();
        } catch (ExternalServiceException | WebClientException failure) {
            var problem = unavailable();
            problem.initCause(failure);
            throw problem;
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public void checkMedia(Long userId, AuditScene scene, String snapshotUrl) {
        var reservation = reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(scene).targetId(userId)
                .mediaItems(List.of(AuditReserveBO.MediaItem.builder().mediaType(MediaType.IMAGE)
                        .mediaUrl(snapshotUrl).build())).build());
        if (reservation == null || reservation.getMediaItems() == null || reservation.getMediaItems().size() != 1) {
            throw unavailable();
        }
        Long logId = reservation.getMediaItems().get(0).getAuditLogId();
        if (logId == null) throw unavailable();
        long deadline = System.nanoTime() + Math.min(10000, Math.max(0, mediaCheckWaitMillis)) * 1_000_000;
        do {
            ContentAuditLog result = logs.selectById(logId);
            if (result == null || !userId.equals(result.getTargetId()) || !Integer.valueOf(scene.getCode()).equals(result.getTargetType())) {
                throw unavailable();
            }
            if (Integer.valueOf(AuditStatus.PASS.getCode()).equals(result.getFinalResult())) return;
            if (Integer.valueOf(AuditStatus.REJECT.getCode()).equals(result.getFinalResult())) throw rejected();
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(Math.min(100, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                var problem = unavailable();
                problem.initCause(interrupted);
                throw problem;
            }
        } while (System.nanoTime() < deadline);
        throw unavailable();
    }

    public static ContractProblemException unavailable() {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/profile-safety-check-unavailable", "资料安全检查暂不可用，本次修改未生效");
    }

    public static ContractProblemException rejected() {
        return new ContractProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "/problems/profile-content-rejected", "资料内容未通过安全检查，本次修改未生效");
    }
}
