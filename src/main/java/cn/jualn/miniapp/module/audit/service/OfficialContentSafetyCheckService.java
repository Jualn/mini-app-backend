package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.reactive.function.client.WebClientException;

@Service @RequiredArgsConstructor
public class OfficialContentSafetyCheckService implements OfficialContentSafetyChecks {
    private final WxClient wx;
    private final UserWechatIdentityService users;
    private final AuditReservationService reservations;
    private final ContentAuditLogMapper logs;

    @Override @Transactional(propagation=Propagation.NEVER)
    public void text(long authenticatedAccountId,String content) {
        if(content==null || content.isBlank()) return;
        try {
            // Existing admin sessions are issued against user_profile; the trusted session supplies this account ID.
            String openid=users.getMiniOpenid(authenticatedAccountId);
            if(openid==null || openid.isBlank()) throw OfficialContentSafetyChecks.unavailable();
            for(int offset=0;offset<content.length();) {
                int end=content.offsetByCodePoints(offset,Math.min(2000,content.codePointCount(offset,content.length())));
                var response=wx.msgSecCheck(WxMsgSecCheckRequest.builder().openid(openid).content(content.substring(offset,end)).version(2).scene(1).build());
                String result=response==null?null:response.getResultSuggest();
                if(response==null || !Integer.valueOf(0).equals(response.getErrcode()) || (response.getDetail()!=null && response.getDetail().stream().anyMatch(detail->
                        detail==null || !Integer.valueOf(0).equals(detail.getErrcode()) || ("pass".equals(result) && !"pass".equals(detail.getSuggest()))))) throw OfficialContentSafetyChecks.unavailable();
                if("risky".equals(result) || "reject".equals(result)) throw rejected();
                if(!"pass".equals(result)) throw OfficialContentSafetyChecks.unavailable();
                offset=end;
            }
        } catch(ExternalServiceException | WebClientException | BusinessException failure) {
            var problem=OfficialContentSafetyChecks.unavailable(); problem.initCause(failure); throw problem;
        }
    }
    @Override @Transactional(propagation=Propagation.NEVER)
    public void media(long authenticatedAccountId,long contentId,List<MediaAttachmentBO> attachments) {
        if(attachments.stream().anyMatch(a->!"IMAGE".equals(a.getKind()))) throw OfficialContentSafetyChecks.unavailable();
        if(attachments.isEmpty()) return;
        if(attachments.stream().anyMatch(image->!Boolean.TRUE.equals(image.getVerified()) || image.getObjectKey()==null)) throw OfficialContentSafetyChecks.unavailable();
        var reserved=reservations.reserveAuditLogs(AuditReserveBO.builder().auditScene(AuditScene.OFFICIAL_CONTENT_CANDIDATE)
                .authenticatedActorAccountId(authenticatedAccountId).targetId(contentId)
                .mediaItems(attachments.stream().map(image->AuditReserveBO.MediaItem.builder()
                        .mediaType(MediaType.IMAGE).mediaUrl(image.getUrl()).build()).toList()).build());
        if(reserved==null || reserved.getMediaItems()==null || reserved.getMediaItems().size()!=attachments.size()) throw OfficialContentSafetyChecks.unavailable();
        var logIds=reserved.getMediaItems().stream().map(item->item.getAuditLogId()).toList();
        if(logIds.stream().anyMatch(java.util.Objects::isNull) || new java.util.HashSet<>(logIds).size()!=logIds.size()) throw OfficialContentSafetyChecks.unavailable();
        long deadline=System.nanoTime()+10_000_000_000L;
        do {
            boolean complete=true;
            for(Long logId:logIds) {
                var result=logs.selectById(logId);
                if(result==null || !Long.valueOf(contentId).equals(result.getTargetId()) || !Integer.valueOf(AuditScene.OFFICIAL_CONTENT_CANDIDATE.getCode()).equals(result.getTargetType())) throw OfficialContentSafetyChecks.unavailable();
                if(Integer.valueOf(AuditStatus.REJECT.getCode()).equals(result.getFinalResult())) throw rejected();
                complete &= Integer.valueOf(AuditStatus.PASS.getCode()).equals(result.getFinalResult());
            }
            if(complete) return;
            if(System.nanoTime()>=deadline) throw OfficialContentSafetyChecks.unavailable();
            try { Thread.sleep(100); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw OfficialContentSafetyChecks.unavailable(); }
        } while(true);
    }
    private ContractProblemException rejected() { return ContractProblemException.conflict("content-rejected","Content was rejected by safety checks"); }
}
