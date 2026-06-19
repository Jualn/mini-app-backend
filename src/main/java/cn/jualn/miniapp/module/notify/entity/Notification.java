package cn.jualn.miniapp.module.notify.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification")
public class Notification {

    /** 通知ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收用户ID */
    private Long userId;

    /** 通知类型：1-评论 2-回复 3-点赞 4-活动提醒 5-考试提醒 6-审核结果 7-系统广播 */
    private Integer type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 关联内容类型：1-帖子 2-活动 3-考试 4-评论，点击跳转用 */
    private Integer targetType;

    /** 关联内容ID */
    private Long targetId;

    /** 触发者用户ID，NULL=系统 */
    private Long senderId;

    /** 0-未读 1-已读 */
    private Integer isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
