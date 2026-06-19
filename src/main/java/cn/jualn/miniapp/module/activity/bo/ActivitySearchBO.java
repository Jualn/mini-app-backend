package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySearchBO {
    private Long id;

    private String title;

    private String content;

    private String location;

    private ActivityStatus status;

    private LocalDateTime publishedAt;
}
