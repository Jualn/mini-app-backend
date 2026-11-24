package cn.jualn.miniapp.module.comment.bo;

import java.util.*;
import lombok.Data;
import lombok.Builder;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;

@Data
@Builder
public class CommentReadContext {
    private Map<Long, UserSimpleBO> userMap;
    private Map<Long, UserSimpleBO> replyUserMap;
    private Map<Long, Boolean> likedMap;
    private Map<Long, Integer> likeCountMap;
    private Map<Long, List<ReplyItemBO>> previewRepliesMap;
}
