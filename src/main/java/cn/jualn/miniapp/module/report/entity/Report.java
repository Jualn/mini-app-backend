package cn.jualn.miniapp.module.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 举报记录实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("report")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reporterId;

    private Integer targetType;

    private Long targetId;

    private Integer reason;

    /** 补充说明（可选） */
    private String remark;

    /** 处理状态 */
    private Integer status;

    /** 处理管理员id */
    private Long handlerId;

    /** 处理备注 */
    private String handleRemark;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
}

