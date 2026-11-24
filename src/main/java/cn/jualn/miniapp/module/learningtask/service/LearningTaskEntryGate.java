package cn.jualn.miniapp.module.learningtask.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import org.springframework.stereotype.Component;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;

/** No feature flag can turn incomplete HTTP implementations into successful operations. */
@Component
public class LearningTaskEntryGate {
    public void reject(boolean admin) {
        if(admin) { AdminStpUtil.STP_LOGIC.checkLogin(); } else { StpUtil.checkLogin(); }
        throw unavailable(Dependency.AUTHORIZATION);
    }
}
