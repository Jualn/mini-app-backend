package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/** 已停用审核链的兼容拒绝入口，不发布或修改活动。 */
@Service
@RequiredArgsConstructor
public class ActivityReviewService {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void approveActivityReview(Long activityId, Long operatorId, String remark) {
        authorization.require(operatorId,"review:decide","ACTIVITY","review:read","review:decide");

        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void rejectActivityReview(Long activityId, Long operatorId, String reason) {
        authorization.require(operatorId,"review:decide","ACTIVITY","review:read","review:decide");

        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void submitAdminActivityReview(Long activityId, Long operatorId) {
        throw new BusinessException(ResultCode.INVALID_OPERATION, "活动暂不接入审核，请使用直接发布接口");
    }
}
