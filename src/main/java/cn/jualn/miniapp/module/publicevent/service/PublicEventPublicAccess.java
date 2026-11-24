package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** PublicEvent owner 的发布资格，与事项生命周期及订阅资格分开。 */
@Service
@RequiredArgsConstructor
public class PublicEventPublicAccess {
    private final PublicEventMapper events;

    public boolean isPubliclyVisible(Long id) {
        return id != null && events.existsPublicById(id);
    }

    public void require(Long id, boolean mutation) {
        PublicEvent event = id == null ? null : mutation ? events.selectForUpdate(id) : events.selectById(id);
        requirePublished(event);
    }

    public static void requirePublished(PublicEvent event) {
        if (event == null || event.getDeletedAt() != null
                || !Integer.valueOf(1).equals(event.getPublishStatus())) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
    }
}
