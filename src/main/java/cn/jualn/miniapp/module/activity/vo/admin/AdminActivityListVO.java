package cn.jualn.miniapp.module.activity.vo.admin;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@Data
@Builder
public class AdminActivityListVO {

    private OffsetDateTime deletedAt;
    private cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO.Availability stage;
    private String activityId;
    private String title;
    private String publishStatus;
    private String lifecycleStatus;
    private OffsetDateTime updatedAt;
}
