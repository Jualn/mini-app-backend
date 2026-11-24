package cn.jualn.miniapp.module.post.vo;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.post.bo.PostCategoryBO;
import cn.jualn.miniapp.module.post.bo.PostTopicBO;
import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情响应。
 */
@Data
@Builder
public class PostDetailVO {

    private Long id;
    private String postType;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private cn.jualn.miniapp.module.post.bo.MentorQaBO.Summary qaSummary;
    private PostCategoryBO category;
    private List<PostTopicBO> topics;
    private PostWritePermission commentPermission;
    private String title;
    private String content;
    private Integer likeCount;
    private Integer commentCount;
    private Integer viewCount;
    private LocalDateTime publishedAt;
    private UserSimpleBO author;
    private List<MediaAttachmentSimpleBO> attachments;
    private Boolean liked;
}
