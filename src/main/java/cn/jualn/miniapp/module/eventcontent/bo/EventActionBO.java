package cn.jualn.miniapp.module.eventcontent.bo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventActionBO {
    private Long id;
    private Integer actionType;
    private String label;
    private String description;
    private String targetValue;
    private Long attachmentId;
    private String attachmentObjectKey;
    private Boolean isRequired;
    private Integer sortOrder;
}

