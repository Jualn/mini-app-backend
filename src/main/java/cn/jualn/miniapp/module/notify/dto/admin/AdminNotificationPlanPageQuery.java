package cn.jualn.miniapp.module.notify.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminNotificationPlanPageQuery {

    @Size(max = 64, message = "关键词不能超过64字")
    private String keyword;

    @Pattern(regexp = "pending|sent|cancelled", message = "通知计划状态不合法")
    private String status;

    @Pattern(regexp = "activity|exam|system", message = "通知来源不合法")
    private String sourceType;

    @Pattern(regexp = "subscribers|all", message = "发送范围不合法")
    private String scope;

    @Size(max = 256, message = "分页游标过长")
    private String cursor;

    @Min(value = 1, message = "每页至少返回1条")
    @Max(value = 100, message = "每页最多返回100条")
    private Integer pageSize = 20;
}
