package cn.jualn.miniapp.module.exam.vo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionVO {
    private Long id;
    private String sectionType;
    private String title;
    private String content;
    private Integer sortOrder;
}
