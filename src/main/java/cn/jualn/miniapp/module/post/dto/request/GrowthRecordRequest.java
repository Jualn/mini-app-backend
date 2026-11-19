package cn.jualn.miniapp.module.post.dto.request;

import cn.jualn.miniapp.module.media.bo.GrowthMediaBO;
import cn.jualn.miniapp.module.post.PostProblems;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;

public final class GrowthRecordRequest {
    private GrowthRecordRequest() {}
    public record Create(String organizationId,String recordType,String title,String content,List<Long> mediaIds,Long categoryId,List<Long> topicIds) {
        @JsonAnySetter public void unknown(String key,Object value){throw PostProblems.invalid(key,"UNKNOWN_FIELD","Unknown growth field");}
    }
    public record Upload(String organizationId,String recordType,List<GrowthMediaBO.Declaration> files,Long postId) {
        @JsonAnySetter public void unknown(String key,Object value){throw PostProblems.invalid(key,"UNKNOWN_FIELD","Unknown growth field");}
    }
    public record Register(String uploadId,Integer fileIndex) {
        @JsonAnySetter public void unknown(String key,Object value){throw PostProblems.invalid(key,"UNKNOWN_FIELD","Unknown growth field");}
    }
}
