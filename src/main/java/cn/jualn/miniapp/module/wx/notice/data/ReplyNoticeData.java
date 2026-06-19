package cn.jualn.miniapp.module.wx.notice.data;

import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 回复通知数据载荷。
 * 对应 NotifyType.REPLIED_ME
// *
// * @param targetId      内容ID
// * @param commentId     被回复的评论ID
// * @param postTitle     内容标题 → YAML source: postTitle
 * @param replyContent  回复内容摘要 → YAML source: replyContent
 * @param replyUserName 回复人昵称 → YAML source: replyUserName
 * @param replyTime     回复时间 → YAML source: replyTime
 */
public record ReplyNoticeData(
        String originalContent,
        String replyContent,
        String replyUserName,
        LocalDateTime replyTime
) implements NoticeData {

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                WxNoticeKeys.ORIGINAL_CONTENT, originalContent != null ? originalContent : "",
                WxNoticeKeys.REPLY_USER_NAME, replyUserName != null ? replyUserName : "",
                WxNoticeKeys.REPLY_CONTENT, replyContent != null ? replyContent : "",
                WxNoticeKeys.REPLY_TIME, replyTime
        );
    }
}
