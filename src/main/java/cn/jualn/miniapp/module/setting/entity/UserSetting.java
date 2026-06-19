package cn.jualn.miniapp.module.setting.entity;

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
@TableName("user_setting")
public class UserSetting {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private Boolean notifyComment;

    private Boolean notifyReply;

    private Boolean notifyLike;

    private Boolean notifyActivityRemind;

    private Boolean notifyExamRemind;

    private Boolean notifySystem;

    private Boolean notifyAuditResult;

    private Boolean privacyShowLikes;

    private Boolean privacyAllowFollow;

    private Boolean privacyAllowMessage;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

}
