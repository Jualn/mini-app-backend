package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanItemBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanPageBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanQueryBO;
import cn.jualn.miniapp.module.notify.bo.AdminNotificationPlanSummaryBO;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.service.AdminNotificationPlanService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminNotificationPlanServiceImpl implements AdminNotificationPlanService {

    private static final String CURSOR_SORT = "latest";

    private final NotifyPlanMapper notifyPlanMapper;

    @Override
    public AdminNotificationPlanPageBO pagePlans(AdminNotificationPlanQueryBO query) {
        AdminNotificationPlanQueryBO actual = query == null
                ? AdminNotificationPlanQueryBO.builder().pageSize(20).build()
                : query;
        int pageSize = actual.getPageSize() == null ? 20 : actual.getPageSize();
        Long lastId = AdminIdCursorCodec.decode(actual.getCursor(), CURSOR_SORT);

        LambdaQueryWrapper<NotifyPlan> wrapper = new LambdaQueryWrapper<NotifyPlan>()
                .select(
                        NotifyPlan::getId,
                        NotifyPlan::getSourceType,
                        NotifyPlan::getSourceId,
                        NotifyPlan::getNotifyType,
                        NotifyPlan::getTitle,
                        NotifyPlan::getContent,
                        NotifyPlan::getScope,
                        NotifyPlan::getScene,
                        NotifyPlan::getSendAt,
                        NotifyPlan::getStatus,
                        NotifyPlan::getCreatedBy,
                        NotifyPlan::getCreatedAt)
                .eq(actual.getStatus() != null, NotifyPlan::getStatus, actual.getStatus())
                .eq(actual.getSourceType() != null, NotifyPlan::getSourceType, actual.getSourceType())
                .eq(actual.getScope() != null, NotifyPlan::getScope, actual.getScope())
                .lt(lastId != null, NotifyPlan::getId, lastId)
                .orderByDesc(NotifyPlan::getId)
                .last("LIMIT " + (pageSize + 1));
        if (actual.getKeyword() != null) {
            Long keywordId = parseId(actual.getKeyword());
            wrapper.and(nested -> {
                nested.like(NotifyPlan::getTitle, actual.getKeyword())
                        .or().like(NotifyPlan::getContent, actual.getKeyword())
                        .or().like(NotifyPlan::getScene, actual.getKeyword());
                if (keywordId != null) {
                    nested.or().eq(NotifyPlan::getId, keywordId);
                }
            });
        }

        List<NotifyPlan> rows = notifyPlanMapper.selectList(wrapper);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminNotificationPlanItemBO> items = rows.stream().map(this::toItemBO).toList();

        return AdminNotificationPlanPageBO.builder()
                .items(items)
                .summary(loadSummary())
                .hasMore(hasMore)
                .nextCursor(items.isEmpty()
                        ? null
                        : AdminIdCursorCodec.encode(CURSOR_SORT, items.get(items.size() - 1).getId()))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminNotificationPlanItemBO getPlan(Long planId) {
        NotifyPlan plan = notifyPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_NOT_FOUND, "通知计划不存在");
        }
        return toItemBO(plan);
    }

    private AdminNotificationPlanSummaryBO loadSummary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        return AdminNotificationPlanSummaryBO.builder()
                .pending(notifyPlanMapper.selectCount(new LambdaQueryWrapper<NotifyPlan>()
                        .eq(NotifyPlan::getStatus, 0)))
                .scheduledToday(notifyPlanMapper.selectCount(new LambdaQueryWrapper<NotifyPlan>()
                        .ge(NotifyPlan::getSendAt, todayStart)
                        .lt(NotifyPlan::getSendAt, tomorrowStart)))
                .sentToday(notifyPlanMapper.selectCount(new LambdaQueryWrapper<NotifyPlan>()
                        .eq(NotifyPlan::getStatus, 1)
                        .ge(NotifyPlan::getSendAt, todayStart)
                        .lt(NotifyPlan::getSendAt, tomorrowStart)))
                .systemBroadcasts(notifyPlanMapper.selectCount(new LambdaQueryWrapper<NotifyPlan>()
                        .eq(NotifyPlan::getSourceType, 3)))
                .build();
    }

    private AdminNotificationPlanItemBO toItemBO(NotifyPlan plan) {
        return AdminNotificationPlanItemBO.builder()
                .id(plan.getId())
                .sourceType(plan.getSourceType())
                .sourceId(plan.getSourceId())
                .notifyType(plan.getNotifyType())
                .title(plan.getTitle())
                .content(plan.getContent())
                .scope(plan.getScope())
                .scene(plan.getScene())
                .sendAt(plan.getSendAt())
                .status(plan.getStatus())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .build();
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
