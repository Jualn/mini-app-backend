package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.bo.ActivityStatisticsBO;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ActivityStatisticsQueries {
    private final CurrentAuthorization authorization;
    private final ActivityMapper mapper;
    public ActivityStatisticsBO read(long id, long operator) {
        authorization.requireActivityRegistrationRead(operator);
        cn.jualn.miniapp.module.activity.bo.ActivityStatisticsBO result;
        try { result = mapper.statistics(id); }
        catch (org.springframework.dao.DataAccessResourceFailureException
                | org.springframework.dao.TransientDataAccessException
                | org.springframework.dao.RecoverableDataAccessException unavailable) {
            throw new cn.jualn.miniapp.common.exception.ContractProblemException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Activity statistics are temporarily unavailable");
        }
        if (result == null) throw new BusinessException(ResultCode.NOT_FOUND);
        return result;
    }
}
