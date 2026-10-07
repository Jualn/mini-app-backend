package cn.jualn.miniapp.module.activity.bo;

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

    private String summary;

    private String location;

    private Integer publishStatus;

    private Integer lifecycleStatus;

    private LocalDateTime publishedAt;
}
