package cn.jualn.miniapp.module.officialcontent.vo;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OfficialContentVO {
    private String id;
    private String title;
    private String summary;
    private String body;
    private String publisher;
    private String category;
    private String publishStatus;
    private String publishedAt;
    private List<Media> images;
    private Media video;
    public record Media(String attachmentId, String kind, String name, String url) {}
}
