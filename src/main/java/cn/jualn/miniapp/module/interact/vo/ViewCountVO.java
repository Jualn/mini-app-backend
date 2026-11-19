package cn.jualn.miniapp.module.interact.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 浏览量响应。
 */
@Data
@Builder
public class ViewCountVO {

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=InteractionCountSerializer.class)
    private Long count;
}

