package cn.jualn.miniapp.module.activity.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminActivityPageQuery {

    @Size(max = 128, message = "关键词不能超过128字")
    private String keyword;

    @Pattern(regexp = "draft|reviewing|enrolling|ongoing|ended|cancelled|rejected",
            message = "活动状态不合法")
    private String status;

    @Pattern(regexp = "other|culture|volunteer|ideology|lecture|sports", message = "活动分类不合法")
    private String category;

    @Pattern(regexp = "college|information|science|finance|humanities|foundation",
            message = "参与范围不合法")
    private String audience;

    @Pattern(regexp = "latest|soonest|most-subscribed", message = "排序方式不合法")
    private String sort = "latest";

    @Size(max = 256, message = "分页游标过长")
    private String cursor;

    @Min(value = 1, message = "每页至少返回1条")
    @Max(value = 100, message = "每页最多返回100条")
    private Integer pageSize = 20;
}
