package cn.jualn.miniapp.module.post.bo;

import java.time.*;
import java.util.List;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class GrowthRecordBO {
    private GrowthRecordBO() {}
    public record Link(long postId,String personId,String organizationId,String recordType) {}
    public record Create(String organizationId,String recordType,String title,String content,List<Long> mediaIds,Long categoryId,List<Long> topicIds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lifecycle(OffsetDateTime acceptedAt,String submissionVersion,@JsonInclude(JsonInclude.Include.ALWAYS) String effectiveVersion,String visibility,String safety,boolean authorDeleted,boolean adminHidden,OffsetDateTime temporaryVisibleUntil) {}
    public record Record(Link link,UserSimpleBO author,String title,String content,List<cn.jualn.miniapp.module.media.bo.GrowthMediaBO.File> media,OffsetDateTime publishedAt,PostCategoryBO category,List<PostTopicBO> topics,Lifecycle lifecycle,List<String> availableActions,int likeCount,int commentCount,int viewCount,int shareCount) {}
    public record Editable(long postId,String personId,String organizationId,String recordType,String title,String content,List<Long> mediaIds,Long categoryId,List<Long> topicIds) {}
}
