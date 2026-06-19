package cn.jualn.miniapp.module.comment.dto.request;

import cn.jualn.miniapp.common.enums.TargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Comment page query.
 */
@Data
public class CommentPageQuery {

    /** Target type: 1-post, 2-activity, 3-exam, 4-comment. */
    @NotNull(message = "targetType 不能为空")
    private TargetType targetType;

    /** Target id. */
    @NotNull(message = "targetId 不能为空")
    private Long targetId;

    /** Parent comment id, null for top-level. */
    private Long parentId;

    /** Cursor for pagination (last comment id). */
    private Long lastId;

    /** Page size. */
    @Min(value = 1, message = "pageSize 最小为1")
    @Max(value = 50, message = "pageSize 最大为50")
    private Integer pageSize = 20;
}
