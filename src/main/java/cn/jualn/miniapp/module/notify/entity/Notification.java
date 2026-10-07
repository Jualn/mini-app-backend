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

    /** 通知类型代码；稳定 wire 名称由 NotifyType 显式映射，不能使用 ordinal。 */
    private Integer type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 渠道无关的冻结内容版本；旧通知为空。 */
    private Integer contentSchemaVersion;

    /** 渠道无关的冻结内容 JSON；外部投递不得再回查业务 Mapper。 */
    private String contentPayload;

    /** 关联内容类型：1-帖子 2-活动 3-考试 4-评论，点击跳转用 */
    private Integer targetType;

    /** 关联内容ID */
    private Long targetId;

    /** 触发者用户ID，NULL=系统 */
    private Long senderId;

    /** 稳定业务来源，用于 at-least-once 下的收件箱去重。 */
    private String sourceKey;

    /** LEGACY compatibility writer or CANONICAL channel-planned producer. */
    private String inboxGeneration;

    /** Compatibility shadow only; readAt owns the business fact. */
    private Integer isRead;

    private LocalDateTime readAt;

    /** Immutable per-user position assigned on first entry into the IN_APP stream. */
    private Long inboxSeq;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
