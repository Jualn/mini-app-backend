package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.service.ActivityPublicAccess;
import cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
import java.util.List;
import cn.jualn.miniapp.module.media.TestMediaService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentReadServiceTest {
    @Test void postFileEntryUsesLiveVisibilityAndNeverPublishesCandidates() {
        var media=mock(TestMediaService.class);var mapper=mock(cn.jualn.miniapp.module.post.mapper.PostMapper.class);
        var post=cn.jualn.miniapp.module.post.entity.Post.builder().id(55L).status(2).auditStatus(1).postType("NORMAL").adminHidden(false).build();
        when(mapper.selectById(55L)).thenReturn(post);
        var service=new AttachmentReadServiceImpl(media,media,mock(ActivityPublicAccess.class),mock(PublicEventPublicAccess.class),mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentService.class),mock(cn.jualn.miniapp.module.article.service.ArticleService.class),new cn.jualn.miniapp.module.post.service.PostPublicAccess(mapper));
        var file=MediaAttachmentBO.builder().id(91L).retention("FORMAL").build();when(media.getAttachment(91L)).thenReturn(file);when(media.listAttachmentTargets(91L)).thenReturn(List.of(new AttachmentTargetBO(1,55L)));
        assertSame(file,service.getPublicAttachment(91L));post.setAdminHidden(true);assertThrows(BusinessException.class,()->service.getPublicAttachment(91L));
        post.setAdminHidden(false);post.setAuditStatus(0);post.setCommunityVersion(true);post.setTemporaryVisibleUntil(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusSeconds(1));assertThrows(BusinessException.class,()->service.getPublicAttachment(91L));
        post.setAuditStatus(1);file.setRetention("CANDIDATE");assertThrows(BusinessException.class,()->service.getPublicAttachment(91L));
    }
    @Test void officialSubjectControlsItsRegisteredAttachment() {
        var media = mock(TestMediaService.class);
        var official = org.mockito.Mockito.mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentService.class);
        var service = new AttachmentReadServiceImpl(media,media, mock(ActivityPublicAccess.class), mock(PublicEventPublicAccess.class), official, org.mockito.Mockito.mock(cn.jualn.miniapp.module.article.service.ArticleService.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class));
        var value = MediaAttachmentBO.builder().id(7L).build();
        when(media.getAttachment(7L)).thenReturn(value);
        when(media.listAttachmentTargets(7L)).thenReturn(List.of(new AttachmentTargetBO(7, 10L)));
        when(official.isPubliclyVisible(10L)).thenReturn(true);
        assertSame(value, service.getPublicAttachment(7L));
        when(official.isPubliclyVisible(10L)).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.getPublicAttachment(7L));
    }

    @Test
    void subjectOwnerControlsVisibilityWithoutLoadingPublicDetail() {
        var media = mock(TestMediaService.class);
        var activities = mock(ActivityPublicAccess.class);
        var events = mock(PublicEventPublicAccess.class);
        var service = new AttachmentReadServiceImpl(media,media, activities, events, org.mockito.Mockito.mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.article.service.ArticleService.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class));
        var value = MediaAttachmentBO.builder().id(1L).targetId(2L).targetType(TargetType.ACTIVITY.getCode()).build();
        when(media.getAttachment(1L)).thenReturn(value);
        when(media.listAttachmentTargets(1L)).thenReturn(List.of(
                new AttachmentTargetBO(TargetType.ACTIVITY.getCode(), 2L)));
        when(activities.isPubliclyVisible(2L)).thenReturn(true);
        assertSame(value, service.getPublicAttachment(1L));
        when(activities.isPubliclyVisible(2L)).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.getPublicAttachment(1L));
        verifyNoInteractions(events);
    }
}
