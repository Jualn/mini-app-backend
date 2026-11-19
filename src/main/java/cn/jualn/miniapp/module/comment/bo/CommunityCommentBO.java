package cn.jualn.miniapp.module.comment.bo;

import cn.jualn.miniapp.module.post.bo.PostWritePermission;
import cn.jualn.miniapp.module.post.bo.PostCommunityBO.Notice;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class CommunityCommentBO {
    private CommunityCommentBO() { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Submission(Long id,Long postId,@JsonInclude(JsonInclude.Include.ALWAYS) Long parentId,
            OffsetDateTime acceptedAt,String visibility,String safety,List<String> availableActions,Notice notice) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mine(Submission submission,String content,String imageUrl,String kind,cn.jualn.miniapp.module.post.bo.MentorQaBO.Attribution mentorAnswer) { }
    public sealed interface Root permits VisibleRoot,PlaceholderRoot { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VisibleRoot(Long id,String content,String imageUrl,UserSimpleBO author,LocalDateTime createdAt,
            Integer likeCount,boolean liked,int replyCount,List<Reply> previewReplies,PostWritePermission replyPermission,String state,String kind,cn.jualn.miniapp.module.post.bo.MentorQaBO.Attribution mentorAnswer) implements Root { }
    public record PlaceholderRoot(Long id,String state,LocalDateTime createdAt,int replyCount,List<Reply> previewReplies,
            PostWritePermission replyPermission) implements Root { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reply(Long id,Long parentId,String content,UserSimpleBO author,UserSimpleBO replyToUser,
            Long replyToCommentId,LocalDateTime createdAt,Integer likeCount,boolean liked,PostWritePermission replyPermission,String kind) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(Long postId,String availability,Root root,Reply focus,String nextReplyCursor) { }
}
