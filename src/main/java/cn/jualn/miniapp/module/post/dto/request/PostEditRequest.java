package cn.jualn.miniapp.module.post.dto.request;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.media.dto.request.AttachmentItemRequest;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Presence is separate from value: null clears title/category only.
 */
@Getter
public class PostEditRequest {
    private String title;
    private String content;
    private Long categoryId;
    private List<Long> topicIds;
    private List<AttachmentItemRequest> attachmentItems;
    private List<Long> mediaIds;
    @JsonIgnore
    private final Set<String> fields = new HashSet<>();

    public void setTitle(String value) {
        fields.add("title");
        title = value;
    }

    public void setMediaIds(List<Long> value) { fields.add("mediaIds"); mediaIds=value; }

    public void setContent(String value) {
        fields.add("content");
        content = value;
    }

    public void setCategoryId(Long value) {
        fields.add("categoryId");
        categoryId = value;
    }

    public void setTopicIds(List<Long> value) {
        fields.add("topicIds");
        topicIds = value;
    }

    public void setAttachmentItems(List<AttachmentItemRequest> value) {
        fields.add("attachmentItems");
        attachmentItems = value;
    }

    @JsonAnySetter
    public void unknown(String key, Object value) {
        if ("postType".equals(key)) {
            throw ContractProblemException.conflict("post-type-immutable", "Post type cannot be changed");
        }
        throw PostProblems.invalid(key, "UNKNOWN_FIELD", "Unknown edit field");
    }
}
