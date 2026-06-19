package cn.jualn.miniapp.module.post.vo;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
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
