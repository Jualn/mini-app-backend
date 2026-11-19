package cn.jualn.miniapp.module.post.bo;

import java.util.List;
import java.util.Set;

import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;

public record PostEditBO(String title, String content, Long categoryId, List<Long> topicIds,
                         List<AttachmentItemBO> attachmentItems, Set<String> fields) {
}
