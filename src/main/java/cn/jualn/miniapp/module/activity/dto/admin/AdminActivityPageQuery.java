package cn.jualn.miniapp.module.activity.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminActivityPageQuery {

    @Size(min = 1, max = 200, message = "关键词长度必须为1至200字")
    private String q;

    @Pattern(regexp = "DRAFT|PUBLISHED|UNPUBLISHED", message = "发布状态不合法")
    private String publishStatus;

    @Pattern(regexp = "ACTIVE|ENDED|CANCELLED", message = "生命周期状态不合法")
    private String lifecycleStatus;

    @Pattern(regexp = "-updatedAt", message = "排序方式不合法")
    private String sort = "-updatedAt";

    @Min(value = 1, message = "页码至少为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页至少返回1条")
    @Max(value = 100, message = "每页最多返回100条")
    private Integer pageSize = 20;
}
