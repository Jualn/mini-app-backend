package cn.jualn.miniapp.module.subject;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.article.service.ArticleVisibility;
import cn.jualn.miniapp.module.comment.service.CommentSubjectVisibility;
import cn.jualn.miniapp.module.subject.mapper.SubjectExistenceMapper;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubjectExistenceServiceTest {
    @Test void articleCommentVisibilityCannotBeOverriddenByPositiveExistenceCache() {
        var redis=mock(RedisService.class);
        var projection=mock(SubjectExistenceMapper.class);
        var comments=mock(CommentSubjectVisibility.class);
        var articles=mock(ArticleVisibility.class);
        var service=new SubjectExistenceService(redis,projection,comments,articles);
        when(comments.articleVisibility(9L)).thenReturn(false,true,false);
        assertFalse(service.exists(TargetType.COMMENT,9L));
        assertTrue(service.exists(TargetType.COMMENT,9L));
        assertFalse(service.exists(TargetType.COMMENT,9L));
        verifyNoInteractions(redis,projection);
    }
}
