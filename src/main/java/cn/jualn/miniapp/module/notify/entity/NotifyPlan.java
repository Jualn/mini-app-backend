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
@TableName("notify_plan")
public class NotifyPlan {

    /** 计划ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源类型：1-活动 2-考试 3-系统广播（管理员手动创建） */
    private Integer sourceType;

    /** 关联内容ID，系统广播时为NULL */
    private Long sourceId;

    /** Timeline fact and stable rule identity. Null only for pre-V20 legacy plans. */
    private Long timelineId;
    private String ruleKey;
    private Long generation;
    private String recipientScope;

    /** 对应 notification.type */
    private Integer notifyType;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 提醒所引用的业务开始时间快照；外部投递不得重新读取业务表补值。 */
    private LocalDateTime subjectStartsAt;

    /** 提醒所引用的业务地点快照；允许为空。 */
    private String subjectLocation;

    /** 发送范围：0-已订阅用户 1-全员 */
    private Integer scope;

    /** 场景标签，如"活动开始前1小时"，供日志/展示用 */
    private String scene;

    /** 计划发送时间；立即发则等于 created_at */
    private LocalDateTime sendAt;

    /** 0-PENDING 1-COMPLETED(fan-out only) 2-CANCELLED 3-PROCESSING */
    private Integer status;

    /** 创建人，NULL=系统自动生成 */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;

}
