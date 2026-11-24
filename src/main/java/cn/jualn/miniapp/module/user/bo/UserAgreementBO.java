package cn.jualn.miniapp.module.user.bo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前用户的协议同意记录。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAgreementBO {
    private Long userId;
    private String version;
    private LocalDateTime agreedAt;
}
