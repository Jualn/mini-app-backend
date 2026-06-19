package cn.jualn.miniapp.module.post.bo;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostListBO {

    private Long id;
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
