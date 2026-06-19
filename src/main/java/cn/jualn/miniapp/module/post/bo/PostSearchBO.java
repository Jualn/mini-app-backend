package cn.jualn.miniapp.module.post.bo;

import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.common.enums.PostStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostSearchBO {
    private Long id;

    /** 目前用不上，title无实际作用 */
    private String title;

    private String content;

    private PostStatus status;

    private AuditStatus auditStatus;

    private LocalDateTime publishedAt;
}
