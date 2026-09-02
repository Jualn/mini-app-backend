package cn.jualn.miniapp.module.post.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPostActionBO {

    private Long operatorId;
    private Long postId;
    private String reason;
}
