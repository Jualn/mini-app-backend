package cn.jualn.miniapp.module.report.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminReportCasePageQuery {
    @Size(max = 100, message = "关键词不能超过100字")
    private String keyword;
    @Pattern(regexp = "pending|violation|normal", message = "案件状态不合法")
    private String status;
    @Pattern(regexp = "post|comment", message = "举报目标类型不合法")
    private String targetType;
    @Pattern(regexp = "illegal|porn|advertising|false-info|other", message = "举报原因不合法")
    private String reason;
    @Size(max = 512, message = "分页游标不合法")
    private String cursor;
    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 50, message = "每页数量不能超过50")
    private Integer pageSize;
}
