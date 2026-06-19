package cn.jualn.miniapp.module.report.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 举报分页查询参数。
 */
@Data
public class ReportPageQuery {

    /** 游标分页上一页最后一条ID */
    private Long lastId;

    /** 每页大小 */
    @Min(value = 1, message = "页面大小最小为1")
    @Max(value = 50, message = "每页最多50条")
    private Integer pageSize = 20;

    /** 处理状态：0-待处理 1-违规已处理 2-正常已处理 */
    private ReportStatus status;

    /** 举报对象类型：1-帖子 2-评论 */
    private TargetType targetType;

    /** 举报对象ID */
    private Long targetId;
}

