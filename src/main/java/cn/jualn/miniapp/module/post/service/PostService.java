package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;

/**
 * Author-facing publication and deletion. Query and governance capabilities are separate.
 */
public interface PostService {
    PostListBO createPost(PostCreateBO command);

    void removePost(Long postId);
}
