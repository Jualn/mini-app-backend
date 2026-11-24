package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.media.bo.AdminAttachmentReadBO;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.module.media.service.MediaUploadRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MediaAccessService implements MediaAccess {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final MediaAttachmentMapper mediaAttachmentMapper;
    private final MediaQueries queries;
    private final MediaUploadRecordService uploadRecordService;
    private final ArticleMediaService articleMediaService;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy articleOrganizations;

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<AdminAttachmentReadBO> readAdminAttachments(List<Long> ids, long actor) {
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            var decision = authorization.current(actor);
            var metadata = queries.batchGetAttachments(ids);
            var access = mediaAttachmentMapper.selectBatchAccess(ids, actor);
            var result = new ArrayList<AdminAttachmentReadBO>();
            boolean articleAllowed=decision.allows("media:read","ARTICLE");
            var articleIds=ids.stream().filter(id->{var value=metadata.get(id);return value!=null && ("VIDEO".equals(value.getKind()) || value.getObjectKey()!=null && value.getObjectKey().startsWith("article/retained/") || access.stream().anyMatch(r->id.equals(r.getAttachmentId()) && Integer.valueOf(8).equals(r.getTargetType())));}).toList();
            Set<Long> articleReadable=new HashSet<>();
            RuntimeException articleFailure=null;
            if(articleAllowed && !articleIds.isEmpty()) try {articleReadable.addAll(mediaAttachmentMapper.readableArticleMedia(articleIds,actor,true,articleOrganizations.mediaOrganizations()));}catch(RuntimeException failure) {articleFailure=failure;}

            for (Long id : ids) {
                try {
                    var types = access.stream().filter(row -> id.equals(row.getAttachmentId()))
                            .map(row -> TargetType.fromCode(row.getTargetType())).toList();
                    var value = metadata.get(id);
                    boolean articleResource=articleIds.contains(id);
                    if(articleResource && articleFailure!=null) throw articleFailure;
                    if (value == null || ("CANDIDATE".equals(value.getRetention()) && !value.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))
                            || (types.isEmpty() && !Objects.equals(value.getRegisteredBy(),actor)) || (articleResource?!articleReadable.contains(id):!reusableAllowed(decision, types))) {
                        result.add(new AdminAttachmentReadBO(id, "UNAVAILABLE", null, null));
                        continue;
                    }
                    result.add(new AdminAttachmentReadBO(id, "READY", value, null));
                } catch (RuntimeException failure) {
                    AdminQuery.rethrowAuthentication(failure);
                    result.add(new AdminAttachmentReadBO(id,
                            AdminQuery.hidden(failure) ? "UNAVAILABLE" : "ERROR", null,
                            AdminQuery.hidden(failure) ? null : AdminQuery.problem(failure)));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            var problem = AdminQuery.problem(failure);
            return ids.stream().map(id -> new AdminAttachmentReadBO(id, "ERROR", null, problem)).toList();
        }
    }

    private boolean reusableAllowed(cn.jualn.miniapp.module.authorization.model.RbacModels.Decision decision, List<TargetType> types) {
        if (!decision.accountEligible()) {
            return false;
        }
        if (decision.source() == cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY) {
            return decision.legacyPermissions().contains("*") || decision.legacyPermissions().contains("content:manage");
        }
        return types.stream().anyMatch(type -> (type == TargetType.ACTIVITY || type == TargetType.EXAM || type == TargetType.OFFICIAL_CONTENT || type == TargetType.ARTICLE)
                && decision.allows("media:read", cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.namespace(type)));
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void authorizeReusableRead(Long attachmentId, long actor) {
        var readable = queries.getAttachment(attachmentId);
        var currentTargets = queries.listAttachmentTargets(attachmentId);
        if("VIDEO".equals(readable.getKind()) || readable.getObjectKey()!=null && readable.getObjectKey().startsWith("article/retained/") || currentTargets.stream().anyMatch(t->Integer.valueOf(8).equals(t.targetType()))) {
            articleMediaService.authorizeRead();
            var allowed=mediaAttachmentMapper.readableArticleMedia(List.of(attachmentId),actor,true,articleOrganizations.mediaOrganizations());
            if(!allowed.contains(attachmentId)) throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
            return;
        }
        if (currentTargets.isEmpty() && !Objects.equals(readable.getRegisteredBy(), actor))
            throw new BusinessException(ResultCode.MEDIA_ATTACHMENT_NOT_FOUND);
        var decision = authorization.current(actor);
        if (decision.source() == cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY) {
            authorization.require(actor, "content:manage", "OFFICIAL_CONTENT", "media:read");
            return;
        }
        if (!decision.accountEligible()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        var targets = currentTargets;
        var types = new ArrayList<TargetType>();
        for (var target : targets) {
            types.add(TargetType.fromCode(target.targetType()));
        }
        if (Objects.equals(readable.getRegisteredBy(),actor) && decision.source() != cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY && decision.accountEligible()) {
            types.add(uploadRecordService.registeredPurpose(attachmentId, actor));
        }
        if (!reusableAllowed(decision, types)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public void authorizeArticleMedia() { articleMediaService.authorizeRead(); }
}
