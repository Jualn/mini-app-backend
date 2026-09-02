package cn.jualn.miniapp.module.comment.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminCommentActionBO {

    private Long operatorId;
    private Long commentId;
    private String reason;
}
