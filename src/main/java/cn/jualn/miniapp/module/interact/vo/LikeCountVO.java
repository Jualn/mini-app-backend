package cn.jualn.miniapp.module.interact.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 点赞数量响应。
 */
@Data
@Builder
public class LikeCountVO {

    private Long count;
}

