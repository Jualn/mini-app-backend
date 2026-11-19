package cn.jualn.miniapp.module.activity.controller;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.converter.PersonalRegistrationConverter;
import cn.jualn.miniapp.module.activity.vo.MyActivityRegistrationItemVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO;
import cn.jualn.miniapp.module.timeline.service.TimelineSchedulePolicy;
import cn.jualn.miniapp.common.web.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MyActivityRegistrationsController {
    private final ActivityRegistrationService service;
    private final PersonalRegistrationConverter converter;
    @GetMapping("/v1/users/me/activity-registrations")
    public ResponseEntity<CursorPageVO<MyActivityRegistrationItemVO>> list(
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int pageSize) {
        Integer filter = status == null ? null : switch (status) {
            case "SUBMITTED" -> 1; case "CANCELLED" -> 2;
            default -> throw new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.BAD_REQUEST);
        };
        var page = service.pageMine(cursor, filter, pageSize);
        var items = page.items().stream().map(item -> {
            var a = item.activity();
            String category = a.category() == null ? null : switch (a.category()) {
                case 4 -> "LECTURE"; case 1 -> "COMPETITION"; case 5 -> "SPORTS";
                case 2 -> "VOLUNTEERING"; case 3 -> "THEMED"; default -> "OTHER";
            };
            var activity = new MyActivityRegistrationItemVO.RegisteredActivitySummary(a.activityId().toString(), a.title(),
                    category, a.organizer(), a.primaryLocation(), a.mainSchedule() == null ? null : TimelineSchedulePolicy.view(a.mainSchedule()),
                    a.publishStatus() == 1 ? "PUBLISHED" : a.publishStatus() == 2 ? "UNPUBLISHED" : "DRAFT",
                    a.lifecycleStatus() == 1 ? "ENDED" : a.lifecycleStatus() == 2 ? "CANCELLED" : "ACTIVE", a.publicAccess(),
                    new ActivitySummaryVO.Availability(a.stageState(), a.evaluatedAt().atOffset(java.time.ZoneOffset.ofHours(8))));
            return new MyActivityRegistrationItemVO(activity, converter.toVO(item.registration()),
                    StrongEtag.of("activity-registration", item.registration().getId(), item.registration().getContractVersion()));
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CursorPageVO<>(items, page.nextCursor()));
    }
}
