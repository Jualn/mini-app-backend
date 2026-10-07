package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.PostNotificationFactsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PostNotificationFactsServiceImpl implements PostNotificationFactsService {
    private final PostMapper mapper;
    @Override public Fact published(long id) {
        Post row = mapper.selectOne(new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent)
                .eq(Post::getId, id).eq(Post::getStatus, 2));
        if (row == null) return null;
        String preview = row.getContent();
        return new Fact(row.getId(), row.getUserId(), row.getTitle(),
                preview == null || preview.length() <= 80 ? preview : preview.substring(0, 80));
    }
}
