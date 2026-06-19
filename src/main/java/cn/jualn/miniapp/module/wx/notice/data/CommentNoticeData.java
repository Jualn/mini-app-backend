package cn.jualn.miniapp.module.wx.notice.data;

import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 发送微信服务号订阅通知,评论通知数据载荷。
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
        LocalDateTime replyTime
) implements NoticeData {

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                WxNoticeKeys.POST_ID, postId,
                WxNoticeKeys.COMMENT_ID, commentId,
                WxNoticeKeys.POST_TITLE, postTitle != null ? postTitle : "",
                WxNoticeKeys.REPLY_USER_NAME, replyUserName != null ? replyUserName : "",
                WxNoticeKeys.REPLY_CONTENT, replyContent != null ? replyContent : "",
                WxNoticeKeys.REPLY_TIME, replyTime
        );
    }
}
