package cn.jualn.miniapp.module.search.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SearchQuery {

    @NotBlank(message = "keyword 不能为空")
    private String keyword;    // 必填

    private Long lastId;     // 游标

    private Integer pageSize;
}
