package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventCreateBO;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventUpdateBO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.jualn.miniapp.module.publicevent.service.PublicEventService;

/** Retired ordinary-user write adapter; never owns supported event state changes. */
@Service
public class PublicEventServiceImpl implements PublicEventService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createExam(PublicEventCreateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExam(PublicEventUpdateBO command) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }

    /** 旧用户写入口已停用；事项统一由独立管理端身份维护。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeExam(Long id) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "公共事项由运维管理，请使用管理端接口");
    }
}
