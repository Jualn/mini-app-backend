package cn.jualn.miniapp.module.audit.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 内容审核日志实体。
 *
 * <p>记录所有通过微信安全 API 的审核任务（文本同步审核、多媒体异步审核）和管理员人工审核结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("content_audit_log")
public class ContentAuditLog {

    /**
     * 主键，自增 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 审核目标类型：1-帖子 2-活动 3-考试信息 4-评论。TODO: 改成 auditScene枚举, 并对数据库字段进行重命名
     */
    private Integer targetType;

    /**
     * 审核目标内容的 ID。
     */
    private Long targetId;

    /**
     * 审核来源：1-微信安全 API 自动审核 2-管理员人工审核。
     */
    private Integer auditSource;

    /**
     * 微信多媒体审核任务的 traceId（同步审核时为空）。
     * 用于微信回调关联，以及 Redis+DB 双绑定中的 trace 恢复。
     */
    private String wxTraceId;

    /**
     * 微信审核返回的结果编码：0=正常 1=有风险。
     * 仅当 auditSource=微信自动 时填充，管理员人工审核时为空。
     */
    private Integer wxResult;

    /**
     * 微信审核的详细返回信息（JSON 格式）。
     * 包含 suggest、label、detail 等完整响应数据，用于日志追溯。
     */
    private String wxDetail;

    /**
     * 执行人工审核的管理员 ID（仅当 auditSource=管理员人工 时填充）。
     */
    private Long adminUserId;

    /**
     * 管理员人工审核的操作：1=通过 2=拒绝。
     */
    private Integer adminAction;

    /**
     * 管理员人工审核的备注说明。
     */
    private String adminRemark;

    /**
     * 管理员写命令幂等键；自动审核记录为空，人工审核记录全局唯一。
     */
    private String idempotencyKey;

    /**
     * 最终审核结果：0-待审核 1-通过 2-拒绝。
     * 异步审核时初始为 PENDING，微信回调后更新为最终结果。
     * 管理员审核后直接更新为最终结果。
     */
    private Integer finalResult;

    /**
     * 创建时间，自动填充（INSERT 时）。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

}
