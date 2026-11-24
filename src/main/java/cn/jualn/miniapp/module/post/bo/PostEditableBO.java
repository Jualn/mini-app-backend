package cn.jualn.miniapp.module.post.bo;

import java.util.List;

public record PostEditableBO(Long id, String postType, String title, String content,
                             List<PostAttachmentInputBO> attachmentItems, Long categoryId, List<Long> topicIds) {
}
