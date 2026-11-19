package cn.jualn.miniapp.module.activity.controller.admin;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.service.ActivityStatisticsQueries;
import cn.jualn.miniapp.module.activity.vo.admin.ActivityStatisticsVO;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ActivityStatisticsController {
    private final ActivityStatisticsQueries queries;
    @GetMapping("/v1/admin/activities/{activityId}/statistics")
    public ResponseEntity<ActivityStatisticsVO> read(@PathVariable long activityId) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        var result = queries.read(activityId, AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ActivityStatisticsVO(
                result.submittedCount(), result.cancelledCount(), result.registrationTotal(), result.subscriberCount(),
                result.asOf().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime()));
    }
}
