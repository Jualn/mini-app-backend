package cn.jualn.miniapp.module.exam.vo;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionVO {
    private String sectionKey;
    private Integer contentFormat;
    private Long id;
    private String title;
    private String content;
    private Integer sortOrder;
}
