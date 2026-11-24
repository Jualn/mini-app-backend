package cn.jualn.miniapp.module.post.dto.request;

import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.PostDictionarySaveBO;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
public class PostDictionaryRequest {
    private String code, name, status;
    private Integer displayOrder;
    private List<String> postTypes;
    @JsonIgnore
    private final Set<String> fields = new HashSet<>();

    public void setCode(String v) {
        fields.add("code");
        code = v;
    }

    public void setName(String v) {
        fields.add("name");
        name = v;
    }

    public void setStatus(String v) {
        fields.add("status");
        status = v;
    }

    public void setDisplayOrder(Integer v) {
        fields.add("displayOrder");
        displayOrder = v;
    }

    public void setPostTypes(List<String> v) {
        fields.add("postTypes");
        postTypes = v;
    }

    @JsonAnySetter
    public void unknown(String key, Object value) {
        throw PostProblems.invalid(key, "UNKNOWN_FIELD", "Unknown dictionary field");
    }

    public PostDictionarySaveBO command() {
        return new PostDictionarySaveBO(code, name, status, displayOrder, postTypes, Set.copyOf(fields));
    }
}
