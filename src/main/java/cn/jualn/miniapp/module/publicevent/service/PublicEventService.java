package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.module.publicevent.bo.*;

/** Retired user-write compatibility. Operations reject; supported capabilities are separate. */
public interface PublicEventService {
    Long createExam(PublicEventCreateBO command);
    void updateExam(PublicEventUpdateBO command);
    void removeExam(Long id);
}
