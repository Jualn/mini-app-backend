package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.audit.service.AttachmentHistoryReader;
import cn.jualn.miniapp.module.content.service.AttachmentReferenceReader;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AttachmentManagementService {
    private final CurrentAuthorization authorization;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy articleOrganizations;
    private final MediaAttachmentMapper attachments;
    private final MediaUploadRecordMapper uploads;
    private final MediaConverter converter;
    private final AttachmentReferenceReader references;
    private final AttachmentHistoryReader history;

    public record Page(List<MediaAttachmentBO> items, int page, int pageSize, long totalItems) {
    }

    private void authorize(long actor) {
        var decision = authorization.current(actor);
        if (!decision.canUseAdmin())
            throw new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden", "Current administrator is required");
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(long actor, int page, int size) {
        authorize(actor);
        if (page < 1 || size < 1 || size > 100) throw AdminQuery.invalid("page/pageSize");
        var decision = authorization.current(actor);
        boolean legacy = decision.source() == RbacModels.Source.LEGACY
                && (decision.legacyPermissions().contains("*") || decision.legacyPermissions().contains("content:manage"));
        var types = java.util.stream.Stream.of(TargetType.ACTIVITY,
                        TargetType.EXAM, TargetType.OFFICIAL_CONTENT,
                        TargetType.ARTICLE).filter(type -> decision.allows("media:read", CurrentAuthorization.namespace(type)))
                .map(TargetType::getCode).toList();
        var paging = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<MediaAttachment>(page, size);
        // MySQL byte-exact COS identity is not parsed by JSqlParser; count the complete authorized query.
        paging.setOptimizeCountSql(false);
        var values = attachments.selectMine(paging, actor, legacy, types,types.contains(8),types.contains(8)?articleOrganizations.mediaOrganizations():List.of());
        return new Page(converter.toBOList(values), page, size, paging.getTotal());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void delete(long actor, long id) {
        authorize(actor);
        var value = attachments.lock(id);
        if (value == null || !Boolean.TRUE.equals(value.getRegistered()) || Boolean.TRUE.equals(value.getDeleted())
                || !Objects.equals(value.getRegisteredBy(), actor) || value.getTargetType() != null || value.getTargetId() != null)
            throw new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Attachment unavailable");
        if (!"FORMAL".equals(value.getRetention()))
            throw ContractProblemException.conflict("attachment-not-formal", "Wait for candidate cleanup");
        if (references.inUse(id))
            throw ContractProblemException.conflict("attachment-in-use", "Attachment is referenced");
        if (history.retained(id, value.getUrl()))
            throw ContractProblemException.conflict("attachment-retained", "Attachment is retained as historical evidence");
        value.setDeleted(true);
        if (attachments.updateById(value) != 1) throw new IllegalStateException("Attachment deletion failed");
        // Tombstoned formal resource is excluded from all APIs. Remote reclamation uses existing durable upload cleanup.
        if (value.getObjectKey() != null) {
            var upload = uploads.selectForUpdate(value.getObjectKey());
            if (upload != null) {
                upload.setStatus(0);
                upload.setCleanupAfter(AttachmentRegistrationService.now());
                upload.setCleanupStartedAt(AttachmentRegistrationService.now());
                if (uploads.updateById(upload) != 1) throw new IllegalStateException("Deletion intent failed");
            }
        }
    }
}
