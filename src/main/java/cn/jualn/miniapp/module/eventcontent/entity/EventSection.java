package cn.jualn.miniapp.module.eventcontent.entity;

import lombok.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("event_section")
public class EventSection {
    private String sectionKey;
    private Integer contentFormat;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private Integer sortOrder;
    private Integer targetType;
    private Long targetId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

