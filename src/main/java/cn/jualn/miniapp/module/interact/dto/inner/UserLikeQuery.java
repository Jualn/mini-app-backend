package cn.jualn.miniapp.module.interact.dto.inner;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLikeQuery {
    private Long userId;
    private Integer pageSize;
    private Long lastId ;
    private TargetType targetType;
}
