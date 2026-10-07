package cn.jualn.miniapp.module.eventcontent.bo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionBO {
    private String sectionKey;
    private Integer contentFormat;
    private Long id;
    private String title;
    private String content;
    private Integer sortOrder;
}

