package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 公开访问不等于受众发现筛选或正式报名资格；结束/取消不撤回已发布详情。 */
@Service
@RequiredArgsConstructor
public class ActivityPublicAccess {
    private final ActivityMapper activities;

    public boolean isPubliclyVisible(Long id) {
        return id != null && activities.existsPublicById(id);
    }

    public void require(Long id, boolean mutation) {
        Activity activity = id == null ? null : mutation
                ? activities.selectForUpdate(id) : activities.selectByIdNotDeleted(id);
        requirePublished(activity);
    }

    public static void requirePublished(Activity activity) {
        if (activity == null || activity.getDeletedAt() != null
                || !Integer.valueOf(1).equals(activity.getPublishStatus())) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
    }
}
