package cn.jualn.miniapp.module.interact.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("view_log")
public class ViewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer targetType;

    private Long targetId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

}
