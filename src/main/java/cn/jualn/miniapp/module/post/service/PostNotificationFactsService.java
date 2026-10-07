package cn.jualn.miniapp.module.post.service;

public interface PostNotificationFactsService {
    record Fact(long id, long ownerId, String title, String preview) {
        public Fact(long id, long ownerId, String title) { this(id, ownerId, title, null); }
    }
    Fact published(long id);
}
