package cn.jualn.miniapp.module.content.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminContentPageQuery {

    @Size(max = 100, message = "关键词不能超过100字")
    private String keyword;

    @Pattern(regexp = "post|comment", message = "内容类型不合法")
    private String type;

    @Pattern(regexp = "published|pending|rejected|removed", message = "内容状态不合法")
    private String status;

    @Pattern(regexp = "pinned|featured|reported", message = "运营标记不合法")
    private String feature;

    @Pattern(regexp = "latest|most-viewed|most-reported", message = "排序方式不合法")
    private String sort;

    @Size(max = 512, message = "分页游标不合法")
    private String cursor;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 50, message = "每页数量不能超过50")
    private Integer pageSize;
}
