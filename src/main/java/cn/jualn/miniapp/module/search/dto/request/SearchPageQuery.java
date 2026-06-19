package cn.jualn.miniapp.module.search.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class SearchPageQuery {

    @NotBlank(message = "keyword 不能为空")
    private String keyword;

    private TargetType targetType;

    private Long lastId;

    private LocalDateTime lastPublishedAt;

    private Integer pageSize;
}

