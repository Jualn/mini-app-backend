package cn.jualn.miniapp.module.publicevent.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicEventSummaryVO {
    private String publicEventId;
    private String title;
    private String summary;
    private String type;
    private String sourceName;
    private String sourceUrl;
    private String officialUrl;
    private String publishStatus;
    private String lifecycleStatus;
    private PublicEventDetailVO.TimelineNode cardTimeline;
    private Attachment cover;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(String attachmentId, String kind, String name, String url,
            String retention, java.time.OffsetDateTime expiresAt) {
        public Attachment(String id,String kind,String name,String url) {this(id,kind,name,url,null,null);}
    }
}
