package cn.jualn.miniapp.module.wx.support;

import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.wx.notice.data.ActivityRemindNoticeData;
import cn.jualn.miniapp.module.wx.notice.data.BroadcastNoticeData;
import cn.jualn.miniapp.module.wx.notice.data.NoticeData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 NotifyPlan 转换成 NoticeData接口的实现类 的工厂类。
 * <p>
 * 主要用于 NotifyPlan fan-out 路径，构造 plan 级共享的 wxData。
 * <p>
 * 注意：
 * 1. 这里允许查业务表，但只查一次。
 * 2. 不要把这类查询放到 consumer / WxNoticePayloadAssembler / FieldRenderer 里。
 * 3. 这里不负责查用户表，用户相关的数据放到 consumer 里查并放到 payload 的 wxData 里。
 * 4. 这里不负责处理复杂的业务逻辑，只负责简单的数据转换和必要的校验。
 */
@Component
@RequiredArgsConstructor
public class NotifyPlanNoticeDataFactory {

    private final ActivityMapper activityMapper;

    /**
     * 构造 NotifyPlan fan-out 路径使用的 plan 级共享 wxData。
     * <p>
     * 这里允许查业务表，但只查一次。
     * 不要把这类查询放到 consumer / WxNoticePayloadAssembler / FieldRenderer 里。
     */
    public NoticeData build(NotifyPlan plan) {
        NotifyType notifyType = NotifyType.fromCode(plan.getNotifyType());

        if (notifyType == null) {
            return null;
        }

        return switch (notifyType) {
            case ACTIVITY_REMIND -> buildActivityRemindNoticeData(plan);

            case SYSTEM -> new BroadcastNoticeData(
                    plan.getTitle(),
                    plan.getContent(),
                    "系统通知"
            );

            // TODO 如果考试提醒也走 NotifyPlan 延迟链路，在这里补 ExamRemindNoticeData。
            // 不要放到 consumer 中按用户重复查考试表。
            case EXAM_REMIND -> null;

            default -> null;
        };
    }

    private NoticeData buildActivityRemindNoticeData(NotifyPlan plan) {
        if (plan.getSourceId() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_PARAM_INVALID, "活动提醒缺少 sourceId");
        }

        Activity activity = activityMapper.selectById(plan.getSourceId());
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND, "活动不存在");
        }

        return new ActivityRemindNoticeData(
                activity.getId(),
                activity.getTitle(),
                activity.getStartTime(),
                activity.getLocation()
        );
    }
}
