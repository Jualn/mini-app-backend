package cn.jualn.miniapp.module.search.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SearchPageBO {

    private String keyword;

    private TargetType targetType;

    private Long lastId;

    private LocalDateTime lastPublishedAt;

    private Integer pageSize;
}

