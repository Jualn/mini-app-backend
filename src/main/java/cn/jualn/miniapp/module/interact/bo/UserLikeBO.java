package cn.jualn.miniapp.module.interact.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户点赞 BO。
 * <p>包含点赞目标 ID（帖子或评论）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLikeBO {
    private Long id;
    private Long targetId;
}
