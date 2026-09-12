package cn.jualn.miniapp.module.exam.dto.request;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionRequest {
    private Long id;
    private String sectionType;
    private String title;
    private String content;
    private Integer sortOrder;
}
