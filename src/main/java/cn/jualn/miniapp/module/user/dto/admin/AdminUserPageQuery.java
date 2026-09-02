package cn.jualn.miniapp.module.user.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserPageQuery {

    @Size(max = 64, message = "关键词不能超过64字")
    private String keyword;

    @Pattern(regexp = "normal|muted|banned|deactivated", message = "用户状态不合法")
    private String status;

    @Pattern(regexp = "user|operator|admin", message = "用户角色不合法")
    private String role;

    @Pattern(regexp = "latest-login|latest-created", message = "排序方式不合法")
    private String sort = "latest-login";

    @Size(max = 256, message = "分页游标过长")
    private String cursor;

    @Min(value = 1, message = "每页至少返回1条")
    @Max(value = 100, message = "每页最多返回100条")
    private Integer pageSize = 20;
}
