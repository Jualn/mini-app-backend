package cn.jualn.miniapp.module.exam.vo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventActionVO {
    private Long id;
    private Integer actionType;
    private String label;
    private String description;
    private String targetValue;
    private Long attachmentId;
    private Boolean isRequired;
    private Integer sortOrder;
}
