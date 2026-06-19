package cn.jualn.miniapp.module.search.vo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchTabCountVO {

    private TargetType targetType;

    private Long count;
}

