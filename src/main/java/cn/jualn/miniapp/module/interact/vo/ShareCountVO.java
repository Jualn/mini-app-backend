package cn.jualn.miniapp.module.interact.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 分享数响应。
 */
@Data
@Builder
public class ShareCountVO {

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=InteractionCountSerializer.class)
    private Long count;
}

