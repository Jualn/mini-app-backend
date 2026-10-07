package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
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
    private final InteractServiceImpl service = new InteractServiceImpl(mapper, mock(ShareRecordMapper.class), mock(RedisService.class),
            mock(TargetValidator.class), mock(PostMapper.class), mock(ActivityMapper.class), mock(ExamInfoMapper.class),
            mock(PostNotificationFactsService.class), mock(CommentNotificationFactsService.class), mock(UserService.class), mock(NotifyService.class));
    @AfterEach void close() { UserContext.clear(); }
    @Test void unauthenticatedAndOtherOwnerAreDeniedBeforeReadingLikes() {
        assertThrows(BusinessException.class, () -> service.pageUserLikes(new UserLikeQuery()));
        UserContext.setUserId(7L);
        assertThrows(BusinessException.class, () -> service.pageUserLikes(UserLikeQuery.builder().userId(8L).build()));
        verifyNoInteractions(mapper);
    }
    @Test void absentOwnerIsBoundToActorAndLikeCursorIsPreserved() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LikeRecord.class);
        UserContext.setUserId(7L);
        when(mapper.selectList(any())).thenReturn(List.of(LikeRecord.builder().id(19L).targetId(42L).userId(7L).build()));
        var query = new UserLikeQuery(); var results = service.pageUserLikes(query);
        assertEquals(7L, query.getUserId()); assertEquals(19L, results.get(0).getId()); assertEquals(42L, results.get(0).getTargetId());
    }
}
