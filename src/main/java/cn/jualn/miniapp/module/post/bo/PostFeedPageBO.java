package cn.jualn.miniapp.module.post.bo;

import cn.jualn.miniapp.common.result.PageResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class PostFeedPageBO extends PageResult<PostListBO> {
    private final String cursor;

    public PostFeedPageBO(List<PostListBO> list, boolean more, Long next, String cursor) {
        super(list, more, next);
        this.cursor = cursor;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCursor() {
        return cursor;
    }
}
