package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.jualn.miniapp.module.activity.service.ActivityService;

/**
 * Retired ordinary-user write adapter; never owns supported event state changes.
 */
@Service
public class ActivityServiceImpl implements ActivityService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createActivity(ActivityCreateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /**
     * 旧用户写入口已停用；事项统一由独立管理端身份维护。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActivity(ActivityUpdateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }

    /**
     * 旧用户写入口已停用；事项统一由独立管理端身份维护。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeActivity(Long id) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动由运维管理，请使用管理端接口");
    }
}
