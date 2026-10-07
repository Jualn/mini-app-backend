package cn.jualn.miniapp.module.comment.service;

public interface CommentNotificationFactsService {
    record Fact(long id, long ownerId, int targetType, long targetId, String preview) { }
    Fact published(long id);
}
