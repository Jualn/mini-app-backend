package cn.jualn.miniapp.module.interact.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Composite primary key table; use custom SQL for CRUD instead of BaseMapper id operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("view_count_cache")
public class ViewCountCache {

    private Integer targetType;

    private Long targetId;

    private Integer viewCount;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

}
