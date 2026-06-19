package cn.jualn.miniapp.module.post.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 帖子分页查询参数。
 */
@Data
public class PostPageQuery {

    /** 游标分页：上一页最后一条帖子 ID，首次查询可为空。 */
    private Long lastId;

    /** 每页条数。 */
    @Min(value = 1, message = "pageSize 最小为1")
    @Max(value = 50, message = "pageSize 最大为50")
    private Integer pageSize = 20;

    private String keyword;

    /**
     * 帖子状态筛选。
     * <p>仅运营/管理员可使用，默认只查已发布帖子。</p>
     */
    private Integer status;

    private Long userId;
}