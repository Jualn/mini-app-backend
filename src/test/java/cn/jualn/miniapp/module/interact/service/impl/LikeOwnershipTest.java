package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.module.interact.service.InteractionTestServices;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class LikeOwnershipTest {
    private final LikeRecordMapper mapper = mock(LikeRecordMapper.class);
    private final InteractionTestServices service = new InteractionTestServices(mapper, mock(ShareRecordMapper.class), mock(RedisService.class), mock(SubjectExistenceService.class), new cn.jualn.miniapp.module.interact.service.InteractionSubjects(java.util.List.of(new cn.jualn.miniapp.module.post.service.PostInteractionSubject(mock(PostMapper.class),mock(RedisService.class)),new cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject(mock(ActivityMapper.class),mock(RedisService.class)),new cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject(mock(PublicEventMapper.class)))), mock(PostNotificationFactsService.class), mock(CommentNotificationFactsService.class), mock(UserService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.user.service.UserAccountService.class), mock(NotifyService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.article.service.ArticleService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.CommentTargetAccess.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.interact.mapper.ViewLogMapper.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.comment.service.ArticleCommentNotifications.class));
    @AfterEach void close() { UserContext.clear(); }
    @Test void articleCountCannotUseTheLegacyAnonymousReadAllowance() {
        UserContext.clear();
        var denied=assertThrows(BusinessException.class,()->service.counts().getLikeCount(cn.jualn.miniapp.common.enums.TargetType.ARTICLE,42L));
        assertEquals(cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED,denied.getResultCode());
        verifyNoInteractions(mapper);
    }
    @Test void unauthenticatedAndOtherOwnerAreDeniedBeforeReadingLikes() {
        assertThrows(BusinessException.class, () -> service.likeQueries().pageUserLikes(new UserLikeQuery()));
        UserContext.setUserId(7L);
        assertThrows(BusinessException.class, () -> service.likeQueries().pageUserLikes(UserLikeQuery.builder().userId(8L).build()));
        verifyNoInteractions(mapper);
    }
    @Test void absentOwnerIsBoundToActorAndLikeCursorIsPreserved() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LikeRecord.class);
        UserContext.setUserId(7L);
        when(mapper.selectList(any())).thenReturn(List.of(LikeRecord.builder().id(19L).targetId(42L).targetType(1).userId(7L).build()));
        var query = new UserLikeQuery(); var results = service.likeQueries().pageUserLikes(query);
        assertEquals(7L, query.getUserId()); assertEquals(19L, results.get(0).getId()); assertEquals(42L, results.get(0).getTargetId());
    }
}
