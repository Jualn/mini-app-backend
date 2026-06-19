package cn.jualn.miniapp.module.user.entity;

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
@TableName("user_agreement")
public class UserAgreement {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime agreedAt;

}
