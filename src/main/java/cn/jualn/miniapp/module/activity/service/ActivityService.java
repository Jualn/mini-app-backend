package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.bo.*;

/** Retired user-write compatibility. Operations reject; supported capabilities are separate. */
public interface ActivityService {
    Long createActivity(ActivityCreateBO command);
    void updateActivity(ActivityUpdateBO command);
    void removeActivity(Long id);
}
