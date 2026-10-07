package cn.jualn.miniapp.module.media.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;
import java.util.List;
import cn.jualn.miniapp.module.media.service.MediaService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentReadServiceTest {
    @Test
    void subjectOwnerControlsVisibilityWithoutLoadingPublicDetail() {
        var media = mock(MediaService.class);
        var activities = mock(ActivityService.class);
        var events = mock(ExamService.class);
        var service = new AttachmentReadServiceImpl(media, activities, events);
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
