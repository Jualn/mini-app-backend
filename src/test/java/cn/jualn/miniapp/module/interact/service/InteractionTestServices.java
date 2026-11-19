package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.comment.service.ArticleCommentNotifications;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import cn.jualn.miniapp.module.comment.service.CommentTargetAccess;
import cn.jualn.miniapp.module.interact.mapper.ViewLogMapper;
import cn.jualn.miniapp.module.interact.service.InteractionSubjects;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.module.user.service.UserService;

import java.util.*;

import cn.jualn.miniapp.module.interact.service.impl.*;

/** Test fixture wiring; assertions use individual production capabilities, never an aggregate facade. */
public class InteractionTestServices {
    private final InteractionLikes likes;
    private final InteractionLikeQueries likeQueries;
    private final InteractionCounts counts;
    private final InteractionShares shares;
    private final InteractionViews views;

    public InteractionTestServices(LikeRecordMapper likeRecordMapper,
            ShareRecordMapper shareRecordMapper,
            RedisService redisService,
            SubjectExistenceService targetValidator,
            InteractionSubjects subjects,
            PostNotificationFactsService postNotificationFacts,
            CommentNotificationFactsService commentNotificationFacts,
            UserService userService,
            UserAccountService userAccounts,
            NotifyService notifyService,
            ArticleService articleService,
            CommentTargetAccess commentTargetAccess,
            ViewLogMapper viewLogMapper,
            ArticleCommentNotifications articleCommentNotifications) {
        var access = new InteractionAccess(userAccounts, commentTargetAccess);
        var notifications = new LikeNotifications(postNotificationFacts, commentNotificationFacts, userService, notifyService, articleService, commentTargetAccess, articleCommentNotifications, org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostPublicAccess.class));
        var cache = new LikeCache(redisService);
        likes = new InteractionLikes(likeRecordMapper, articleService, access, notifications, cache);
        likeQueries = new InteractionLikeQueries(likeRecordMapper, redisService, access);
        counts = new InteractionCounts(likeRecordMapper, shareRecordMapper, redisService, subjects, articleService, access);
        shares = new InteractionShares(shareRecordMapper, articleService, access, org.mockito.Mockito.mock(cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper.class));
        views = new InteractionViews(redisService, articleService, viewLogMapper, access, org.mockito.Mockito.mock(cn.jualn.miniapp.module.post.service.PostCommunityService.class), org.mockito.Mockito.mock(cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper.class));
    }

    public InteractionLikes likes() { return likes; }

    public InteractionLikeQueries likeQueries() { return likeQueries; }

    public InteractionCounts counts() { return counts; }

    public InteractionShares shares() { return shares; }

    public InteractionViews views() { return views; }
}
