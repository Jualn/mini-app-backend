package cn.jualn.miniapp.module.audit.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminReviewPageQuery {
    @Size(max = 100, message = "关键词不能超过100个字符")
    private String keyword;

    @Pattern(regexp = "post|activity|exam|comment", message = "审核目标类型不合法")
    private String targetType;

    @Pattern(regexp = "high|medium|low", message = "风险等级不合法")
    private String riskLevel;

    @Pattern(regexp = "pending|completed|all", message = "审核任务分组不合法")
    private String tab = "pending";

    @Pattern(regexp = "priority|oldest|newest", message = "排序方式不合法")
    private String sort = "priority";

    @Size(max = 256, message = "分页游标过长")
    private String cursor;

    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize;
}
