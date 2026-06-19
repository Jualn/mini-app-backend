package cn.jualn.miniapp.module.interact.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 点赞状态响应。
 */
@Data
@Builder
public class LikeStatusVO {

    private Boolean liked;
}

