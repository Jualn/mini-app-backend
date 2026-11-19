package cn.jualn.miniapp.module.post.bo;

import java.util.*;

import lombok.Data;
import lombok.Builder;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;

@Data
@Builder
public class PostListContext {
    private Map<Long, UserSimpleBO> userMap;
    private Map<Long, Boolean> likedMap;
    private Map<Long, Integer> likeCountMap;
    private Map<Long, Integer> viewCountMap;
    private Map<Long, List<MediaAttachmentSimpleBO>> attachmentMap;
}

