package cn.jualn.miniapp.module.eventcontent.bo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionBO {
    private Long id;
    private String sectionType;
    private String title;
    private String content;
    private Integer sortOrder;
}

