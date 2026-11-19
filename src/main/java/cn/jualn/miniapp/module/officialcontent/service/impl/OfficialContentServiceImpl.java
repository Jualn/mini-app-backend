package cn.jualn.miniapp.module.officialcontent.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.officialcontent.bo.*;
import cn.jualn.miniapp.module.officialcontent.entity.OfficialContent;
import cn.jualn.miniapp.module.officialcontent.enums.*;
import cn.jualn.miniapp.module.officialcontent.mapper.OfficialContentMapper;
import cn.jualn.miniapp.module.officialcontent.service.*;

import java.time.*;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OfficialContentServiceImpl implements OfficialContentService {

    private final AdminAuditJournal managementAudit;
    private final CurrentAuthorization authorization;
    private final OfficialContentMapper mapper;
    private final MediaService mediaService;
    private final cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries;
    private final OfficialPublicationWriter publicationWriter;
    private final OfficialContentSafetyChecks safety;

    @Override
    public OfficialContentPageBO list(OfficialContentCategory category, OfficialContentStatus status, String cursor, int size, boolean admin) {
        if (admin)
            authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "content:read", "OFFICIAL_CONTENT", "official-content:read");
        if (size < 1 || size > 50) invalid("#/pageSize", "INVALID_VALUE", "pageSize must be 1..50");
        String selectedStatus = admin ? (status == null ? null : status.name()) : "PUBLISHED";
        String context = (admin ? "admin" : "public") + "/" + (category == null ? "*" : category.name()) + "/" + (selectedStatus == null ? "*" : selectedStatus);
        List<OfficialContent> rows = mapper.list(category == null ? null : category.name(), selectedStatus,
                OfficialContentCursor.decode(cursor, context), size + 1);
        boolean more = rows.size() > size;
        List<OfficialContent> page = more ? rows.subList(0, size) : rows;
        return new OfficialContentPageBO(page.stream().map(v -> result(v, List.of())).toList(), more,
                more ? OfficialContentCursor.encode(page.get(page.size() - 1).getId(), context) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public OfficialContentBO get(long id, boolean admin) {
        if (admin)
            authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "content:read", "OFFICIAL_CONTENT", "official-content:read");
        OfficialContent entity = mapper.selectById(id);
        requireVisible(entity, admin);
        return result(entity, mediaQueries.listLinkedAttachments(TargetType.OFFICIAL_CONTENT, id));
    }

    @Override
    public boolean isPubliclyVisible(long id) {
        OfficialContent value = mapper.selectById(id);
        return value != null && "PUBLISHED".equals(value.getPublishStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public OfficialContentBO create(OfficialContentSaveBO command) {
        long operator = requireEditor();
        if (command.publishStatus() != OfficialContentStatus.DRAFT)
            invalid("#/publishStatus", "INVALID_VALUE", "Create as DRAFT first");
        List<AttachmentLinkBO> links = validate(command);
        OfficialContent entity = new OfficialContent();
        apply(entity, command, operator);
        entity.setCreatedBy(operator);
        entity.setCreatedAt(now());
        entity.setRevision(1L);
        if (mapper.insert(entity) != 1) throw new IllegalStateException("Official content insert failed");
        replaceLinks(entity.getId(), links);
        managementAudit.operation(java.util.UUID.randomUUID().toString(), operator, "createOfficialContent", "OFFICIAL_CONTENT", String.valueOf(entity.getId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return result(entity, mediaQueries.listLinkedAttachments(TargetType.OFFICIAL_CONTENT, entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public OfficialContentBO replace(long id, OfficialContentSaveBO command, String ifMatch) {
        long operator = requireEditor();
        OfficialContent entity = mapper.lock(id);
        requireVisible(entity, true);
        managementAudit.confirmed(operator, "replaceOfficialContent", "OFFICIAL_CONTENT", Long.toString(id));
        StrongEtag.require(ifMatch, "official-content", id, entity.getRevision());
        if (command.publishStatus() == OfficialContentStatus.PUBLISHED)
            authorization.require(operator, "content:manage", "OFFICIAL_CONTENT", "official-content:publish");
        if ("PUBLISHED".equals(entity.getPublishStatus()) && command.publishStatus() != OfficialContentStatus.PUBLISHED)
            authorization.require(operator, "content:manage", "OFFICIAL_CONTENT", "official-content:unpublish");
        if (entity.getPublishedAt() != null && command.publishStatus() == OfficialContentStatus.DRAFT)
            throw ContractProblemException.conflict("official-content-state-conflict", "Published content cannot return to DRAFT");
        List<AttachmentLinkBO> links = validate(command);
        apply(entity, command, operator);
        entity.setRevision(entity.getRevision() + 1);
        if (mapper.replace(entity) != 1) throw new IllegalStateException("Official content update failed");
        replaceLinks(id, links);
        managementAudit.operation(java.util.UUID.randomUUID().toString(), operator, "replaceOfficialContent", "OFFICIAL_CONTENT", String.valueOf(id), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return result(entity, mediaQueries.listLinkedAttachments(TargetType.OFFICIAL_CONTENT, id));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public OfficialContentBO publication(long id, boolean publish, String ifMatch) {
        long actor = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        authorization.requireWork(actor, "OFFICIAL_CONTENT_PUBLISH", "OFFICIAL_CONTENT", publish ? new String[]{"official-content:read", "media:read", "official-content:publish"} : new String[]{"official-content:unpublish"});
        var saved = mapper.selectById(id);
        requireVisible(saved, true);
        StrongEtag.require(ifMatch, "official-content", id, saved.getRevision());
        if (!publish && "DRAFT".equals(saved.getPublishStatus()))
            throw ContractProblemException.conflict("official-content-state-conflict", "Cannot unpublish a draft");
        var attachments = mediaQueries.listLinkedAttachments(TargetType.OFFICIAL_CONTENT, id);
        if (publish && !"PUBLISHED".equals(saved.getPublishStatus())) {
            authorization.require(actor, "content:read", "OFFICIAL_CONTENT", "media:read");
            var images = attachments.stream().filter(a -> !Objects.equals(a.getId(), saved.getVideoAttachmentId())).map(MediaAttachmentBO::getId).toList();
            var candidate = new OfficialContentSaveBO(saved.getTitle(), saved.getSummary(), saved.getBody(), saved.getPublisher(),
                    OfficialContentCategory.valueOf(saved.getCategory()), OfficialContentStatus.PUBLISHED, images, saved.getVideoAttachmentId());
            var links = validate(candidate);
            if (links.size() != attachments.size()) throw OfficialContentSafetyChecks.unavailable();
            safety.text(actor, saved.getTitle() + "\n" + saved.getSummary() + "\n" + saved.getPublisher() + "\n" + saved.getBody());
            safety.media(actor, id, attachments);
        }
        var committed = publicationWriter.commit(actor, id, publish, ifMatch, saved.getRevision());
        return result(committed, attachments);
    }

    private void replaceLinks(long id, List<AttachmentLinkBO> links) {
        try {
            mediaService.replaceAttachmentLinks(TargetType.OFFICIAL_CONTENT, id, links);
        } catch (BusinessException ex) {
            invalid("#/imageAttachmentIds", "INVALID_REFERENCE", "Use registered media attachments");
        }
    }

    private long requireEditor() {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "content:manage", "OFFICIAL_CONTENT", "official-content:edit");
        return AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
    }

    private List<AttachmentLinkBO> validate(OfficialContentSaveBO command) {
        if (command.title() == null || command.title().isBlank() || command.title().length() > 120
                || command.body() == null || command.body().isBlank() || command.body().length() > 50000
                || command.summary() == null || command.summary().length() > 500
                || command.publisher() == null || command.publisher().isBlank() || command.publisher().length() > 120
                || command.category() == null || command.publishStatus() == null)
            invalid("#", "INVALID_VALUE", "Content fields are incomplete or too long");
        List<Long> ids = command.imageAttachmentIds();
        if (ids.size() > 20 || new HashSet<>(ids).size() != ids.size())
            invalid("#/imageAttachmentIds", "DUPLICATE_KEY", "Invalid image references");
        List<AttachmentLinkBO> links = new ArrayList<>();
        Map<Long, MediaAttachmentBO> images = mediaQueries.batchGetAttachments(ids);
        for (int i = 0; i < ids.size(); i++) {
            var image = images.get(ids.get(i));
            requireReady(image);
            if (image == null || !"IMAGE".equals(image.getKind()))
                invalid("#/imageAttachmentIds/" + i, "INVALID_REFERENCE", "Use a registered IMAGE attachment");
            links.add(new AttachmentLinkBO(ids.get(i), i));
        }
        if (command.videoAttachmentId() != null) {
            var video = mediaQueries.batchGetAttachments(List.of(command.videoAttachmentId())).get(command.videoAttachmentId());
            requireReady(video);
            if (video == null || !"LINK".equals(video.getKind()) || ids.contains(command.videoAttachmentId()))
                invalid("#/videoAttachmentId", "INVALID_REFERENCE", "Use a registered LINK attachment for the video");
            links.add(new AttachmentLinkBO(command.videoAttachmentId(), ids.size()));
        }
        return links;
    }

    private void requireReady(MediaAttachmentBO value) {
        if (value != null && "CANDIDATE".equals(value.getRetention())) {
            if (!value.getExpiresAt().isAfter(now()))
                throw ContractProblemException.conflict("attachment-candidate-expired", "Candidate expired");
            if (!Boolean.TRUE.equals(value.getVerified()))
                throw ContractProblemException.conflict("attachment-not-ready", "Candidate has not passed technical verification");
        }
    }

    private void apply(OfficialContent entity, OfficialContentSaveBO command, long operator) {
        entity.setTitle(command.title().trim());
        entity.setSummary(command.summary());
        entity.setBody(command.body());
        entity.setPublisher(command.publisher().trim());
        entity.setCategory(command.category().name());
        entity.setPublishStatus(command.publishStatus().name());
        entity.setVideoAttachmentId(command.videoAttachmentId());
        entity.setUpdatedBy(operator);
        entity.setUpdatedAt(now());
        if (command.publishStatus() == OfficialContentStatus.PUBLISHED && entity.getPublishedAt() == null)
            entity.setPublishedAt(now());
    }

    private OfficialContentBO result(OfficialContent entity, List<MediaAttachmentBO> attachments) {
        var images = attachments.stream().filter(v -> !Objects.equals(v.getId(), entity.getVideoAttachmentId())).toList();
        var video = attachments.stream().filter(v -> Objects.equals(v.getId(), entity.getVideoAttachmentId())).findFirst().orElse(null);
        return new OfficialContentBO(entity.getId(), entity.getTitle(), entity.getSummary(), entity.getBody(), entity.getPublisher(),
                entity.getCategory(), entity.getPublishStatus(), entity.getPublishedAt(), entity.getRevision(), images, video);
    }

    private void requireVisible(OfficialContent entity, boolean admin) {
        if (entity == null || (!admin && !"PUBLISHED".equals(entity.getPublishStatus())))
            throw new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Content is unavailable");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    private static void invalid(String pointer, String code, String detail) {
        throw ContractProblemException.validation(new ContractProblemException.Violation("body", pointer, code, detail));
    }
}
