package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.post.bo.PostCommentAccessBO;
import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.post.service.PostCommentPermissions;
import cn.jualn.miniapp.module.post.PostProblems;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class PostQaCommentPolicy {
    private final PostCommentPermissions permissions;

    public void guardWrite(long userId) {
        permissions.guardWrite(userId);
    }

    public String authorize(PostCommentAccessBO post, long actor, Comment parent, Comment target) {
        if (!"MENTOR_QA".equals(post.postType())) {
            if (!"NORMAL".equals(post.postType())) {
                throw forbidden();
            }
            return null;
        }
        if (parent == null) {
            return permissions.requireMentor(actor);
        }
        if(target==null)throw PostProblems.invalid("replyToCommentId","REQUIRED","A concrete public discussion target is required");
        return null;
    }

    public PostWritePermission reply(PostCommentAccessBO post, Long actor, Comment target) {
        return reply(post, actor, target, null);
    }

    public Map<Long, PostWritePermission> replies(PostCommentAccessBO post, Long actor, List<Comment> targets) {
        PostWritePermission mentor = null;
        Map<Long, PostWritePermission> result = new HashMap<>();
        for (var target : targets) {
            result.put(target.getId(), reply(post, actor, target, mentor));
        }
        return result;
    }

    private PostWritePermission reply(PostCommentAccessBO post, Long actor, Comment target, PostWritePermission mentor) {
        if (post == null || "NORMAL".equals(post.postType())) {
            return permissions.answer(actor, "NORMAL");
        }
        if (!"MENTOR_QA".equals(post.postType())) {
            return PostWritePermission.denied("TYPE_UNSUPPORTED");
        }
        if (actor == null) {
            return PostWritePermission.denied("NOT_AUTHORIZED");
        }
        return PostWritePermission.allowed();
    }

    private cn.jualn.miniapp.common.exception.ContractProblemException forbidden() {
        return PostProblems.problem(HttpStatus.FORBIDDEN, "mentor-answer-forbidden", "This account cannot participate in this mentor question");
    }
}
