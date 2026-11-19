package cn.jualn.miniapp.module.notify.payload.data;


import java.time.LocalDateTime;
import java.util.Map;

/**
 * 评论事件的通知内容事实。
 * 用于映射约束 YML 配置文件中的 fields.source
 * 避免调用方传入魔法内容
 *
 * @param postId        内容ID（对应 pagePath 的 {postId}）
 * @param commentId     评论ID
 * @param postTitle     内容标题 → YAML source: postTitle
 * @param replyContent  回复内容摘要 → YAML source: replyContent
 * @param replyUserName 回复人昵称 → YAML source: replyUserName
 * @param replyTime     回复时间 → YAML source: replyTime
 */
public record CommentNoticeData(
        Long   postId,
        Long   commentId,
        String postTitle,
        String replyContent,
        String replyUserName,
        LocalDateTime replyTime,
        String originalContent
) implements NoticeData {

    /** Preserve existing durable payloads and callers without an original-body snapshot. */
    public CommentNoticeData(Long postId, Long commentId, String postTitle, String replyContent,
                             String replyUserName, LocalDateTime replyTime) {
        this(postId, commentId, postTitle, replyContent, replyUserName, replyTime, null);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of(
                NoticeKeys.POST_ID, postId,
                NoticeKeys.COMMENT_ID, commentId,
                NoticeKeys.POST_TITLE, postTitle != null ? postTitle : "",
                NoticeKeys.REPLY_USER_NAME, replyUserName != null ? replyUserName : "",
                NoticeKeys.REPLY_CONTENT, replyContent != null ? replyContent : "",
                NoticeKeys.REPLY_TIME, replyTime
        ));
        if (originalContent != null) result.put(NoticeKeys.ORIGINAL_CONTENT, originalContent);
        return result;
    }
}
