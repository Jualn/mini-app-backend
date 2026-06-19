package cn.jualn.miniapp.module.comment.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Data;

/**
 * Comment page query command.
 */
@Data
public class CommentPageBO {

    private TargetType targetType;
    private Long targetId;
    private Long parentId;
    private Long lastId;
    private Integer pageSize;
}
