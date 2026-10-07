package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class AdminActivityListVO {

    private String activityId;
    private String title;
    private String publishStatus;
    private String lifecycleStatus;
    private OffsetDateTime updatedAt;
}
