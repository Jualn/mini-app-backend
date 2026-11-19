package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.post.PostProblems;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PostCommentPermissions {
    private final MentorQualification qualifications;

    public void guardWrite(long userId) {
        qualifications.guardWrite(userId);
    }

    public PostWritePermission answer(Long userId, String type) {
        if (userId == null) {
            return PostWritePermission.denied("NOT_AUTHORIZED");
        }
        if ("NORMAL".equals(type)) {
            return PostWritePermission.allowed();
        }
        if (!"MENTOR_QA".equals(type)) {
            return PostWritePermission.denied("TYPE_UNSUPPORTED");
        }
        var decision = qualifications.evaluate(userId);
        if (decision != null && decision.valid()) {
            return PostWritePermission.allowed();
        }
        if (decision != null && decision.state() == MentorQualification.State.INELIGIBLE) {
            return PostWritePermission.denied("MENTOR_REQUIRED");
        }
        return PostWritePermission.blocked();
    }

    public String requireMentor(long userId) {
        var decision = qualifications.forWrite(userId);
        if (decision != null && decision.valid()) {
            return decision.evidence();
        }
        if (decision != null && decision.state() == MentorQualification.State.INELIGIBLE) {
            throw PostProblems.problem(HttpStatus.FORBIDDEN, "mentor-answer-forbidden", "Valid alumni mentor qualification is required");
        }
        throw PostProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "mentor-qualification-unavailable", "Mentor qualification cannot currently be verified");
    }
}
