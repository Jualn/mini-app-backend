package cn.jualn.miniapp.module.search.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchTabCountBO {

    private TargetType targetType;

    private Long count;
}

