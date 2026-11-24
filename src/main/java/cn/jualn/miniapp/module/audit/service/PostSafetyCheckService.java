package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.service.PostSafetyChecks;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.HashSet;
import java.util.List;

/**
 * Candidate checks cannot mutate the effective Post. Logs/jobs are owned by audit.
 */
@Service
@RequiredArgsConstructor
public class PostSafetyCheckService implements PostSafetyChecks {
    private final WxClient wx;
    private final UserWechatIdentityService users;
    private final AuditReservationService reservations;
    private final ContentAuditLogMapper logs;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void check(long userId, long postId, String text, List<AttachmentItemBO> media) {
        try {
            String openid = users.getMiniOpenid(userId);
            if (openid == null || openid.isBlank()) {
                throw unavailable();
            }
            if (text != null) {
                for (int offset = 0; offset < text.length(); ) {
                    int end = text.offsetByCodePoints(offset, Math.min(2000, text.codePointCount(offset, text.length())));
                    var response = wx.msgSecCheck(WxMsgSecCheckRequest.builder().openid(openid).content(text.substring(offset, end)).version(2).scene(3).build());
                    String suggest = response == null ? null : response.getResultSuggest();
                    if (response == null || !Integer.valueOf(0).equals(response.getErrcode())) {
                        throw unavailable();
                    }
                    // The raw response validator confirms the keyword no-hit shape and a passing content model.
                    if (!response.isVerifiedEmptyKeywordPassStructure() && response.getDetail() != null
                            && response.getDetail().stream().anyMatch(detail -> detail == null
                                    || !Integer.valueOf(0).equals(detail.getErrcode())
                                    || ("pass".equals(suggest) && !"pass".equals(detail.getSuggest())))) {
                        throw unavailable();
                    }
                    if ("risky".equals(suggest) || "reject".equals(suggest)) {
                        throw rejected();
                    }
                    if (!"pass".equals(suggest)) {
                        throw unavailable();
                    }
                    offset = end;
                }
            }
            if (media.isEmpty()) {
                return;
            }
            // The existing provider only proves IMAGE safety. Other changed media fail closed.
            if (media.stream().anyMatch(m -> m.getType() != MediaType.IMAGE)) {
                throw unavailable();
            }
            var reserved = reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.POST_EDIT).targetId(postId)
                    .mediaItems(media.stream().map(m -> AuditReserveBO.MediaItem.builder().mediaType(m.getType()).mediaUrl(m.getUrl()).build()).toList()).build());
            if (reserved == null || reserved.getMediaItems() == null || reserved.getMediaItems().size() != media.size()) {
                throw unavailable();
            }
            var ids = reserved.getMediaItems().stream().map(m -> m.getAuditLogId()).toList();
            if (ids.contains(null) || new HashSet<>(ids).size() != ids.size()) {
                throw unavailable();
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            do {
                boolean pass = true;
                for (Long id : ids) {
                    var log = logs.selectById(id);
                    if (log == null || !Long.valueOf(postId).equals(log.getTargetId()) || !Integer.valueOf(AuditScene.POST_EDIT.getCode()).equals(log.getTargetType())) {
                        throw unavailable();
                    }
                    if (Integer.valueOf(AuditStatus.REJECT.getCode()).equals(log.getFinalResult())) {
                        throw rejected();
                    }
                    pass &= Integer.valueOf(AuditStatus.PASS.getCode()).equals(log.getFinalResult());
                }
                if (pass) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    throw unavailable();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw unavailable();
                }
            } while (true);
        } catch (ExternalServiceException | WebClientException | BusinessException failure) {
            var problem = unavailable();
            problem.initCause(failure);
            throw problem;
        }
    }

    private ContractProblemException unavailable() {
        return PostProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "post-safety-check-unavailable", "Safety checks unavailable; previous content retained");
    }

    private ContractProblemException rejected() {
        return PostProblems.problem(HttpStatus.UNPROCESSABLE_ENTITY, "post-content-rejected", "Content rejected; previous content retained");
    }
}
