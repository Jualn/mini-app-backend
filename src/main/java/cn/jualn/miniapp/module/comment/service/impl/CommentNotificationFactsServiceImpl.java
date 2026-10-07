package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommentNotificationFactsServiceImpl implements CommentNotificationFactsService {
    private final CommentMapper mapper;
    @Override public Fact published(long id) {
        Comment row = mapper.selectOne(new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getUserId, Comment::getTargetType, Comment::getTargetId, Comment::getContent)
                .eq(Comment::getId, id).eq(Comment::getStatus, 1));
        if (row == null) { return null; }
        String preview = row.getContent() == null ? "" : row.getContent();
        return new Fact(row.getId(), row.getUserId(), row.getTargetType(), row.getTargetId(),
                preview.length() > 80 ? preview.substring(0, 80) : preview);
    }
}
