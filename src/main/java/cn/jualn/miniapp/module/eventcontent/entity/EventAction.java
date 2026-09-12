package cn.jualn.miniapp.module.eventcontent.entity;

import lombok.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("event_action")
public class EventAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer actionType;
    private String label;
    private String description;
    private String targetValue;
    private Long attachmentId;
    private Boolean isRequired;
    private Integer sortOrder;
    private Integer targetType;
    private Long targetId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

