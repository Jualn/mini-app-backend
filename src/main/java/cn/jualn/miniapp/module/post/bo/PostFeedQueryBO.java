package cn.jualn.miniapp.module.post.bo;

import lombok.Data;

/**
 * 帖子分页查询参数。
 */
@Data
public class PostFeedQueryBO {

    /**
     * 游标分页：上一页最后一条帖子 ID，首次查询可为空。
     */
    private Long lastId;
    private String postType = "NORMAL";
    private Long categoryId;
    private Long topicId;
    private String cursor;
    private String answerState;

    /**
     * 每页条数。
     */
    private Integer pageSize = 20;

    private String keyword;

    /**
     * 帖子状态筛选。
     * <p>仅运营/管理员可使用，默认只查已发布帖子。</p>
     */
    private Integer status;

    private Long userId;
}