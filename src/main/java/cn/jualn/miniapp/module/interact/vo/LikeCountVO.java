package cn.jualn.miniapp.module.interact.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 点赞数量响应。
 */
@Data
@Builder
public class LikeCountVO {

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=InteractionCountSerializer.class)
    private Long count;
}

