package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.bo.AdminPublicEventQueryBO;
import cn.jualn.miniapp.module.publicevent.bo.AdminPublicEventPageBO;
import cn.jualn.miniapp.module.publicevent.bo.AdminPublicEventListBO;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 管理公共事项查询，不拥有写状态规则。 */
@Service
@RequiredArgsConstructor
public class PublicEventAdminQueries {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final PublicEventMapper examInfoMapper;
    private final PublicEventQueries queries;

    public AdminPublicEventPageBO pageAdminPublicEvents(AdminPublicEventQueryBO query) {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"public-event:read","PUBLIC_EVENT","public-event:read");

        int page = query.getPage() == null ? 1 : query.getPage();
        int size = query.getPageSize() == null ? 20 : query.getPageSize();
        if (page < 1 || size < 1 || size > 100 || !"-updatedAt".equals(query.getSort()))
            throw new BusinessException(ResultCode.BAD_REQUEST);
        long offset = Math.multiplyExact((long) page - 1, size);
        List<AdminPublicEventListBO> items = examInfoMapper.selectOperationsPage(query, offset, size);
        return AdminPublicEventPageBO.builder().items(items).page(page).pageSize(size)
                .totalItems(examInfoMapper.countOperationsPage(query)).build();
    }

    public PublicEventDetailBO getAdminPublicEvent(Long id) {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"public-event:read","PUBLIC_EVENT","public-event:read");
 return queries.loadPublicEventDetail(requireExam(id)); }

    private PublicEvent requireExam(Long examId) {
        PublicEvent examInfo = examInfoMapper.selectByIdNotDeleted(examId);
        if (examInfo == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return examInfo;
    }
}
