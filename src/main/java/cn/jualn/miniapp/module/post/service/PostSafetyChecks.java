package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;

import java.util.List;

public interface PostSafetyChecks {
    void check(long userId, long postId, String text, List<AttachmentItemBO> media);
}
