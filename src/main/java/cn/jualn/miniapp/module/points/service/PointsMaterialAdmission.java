package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.media.service.PointMaterialAdmission;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;
import static cn.jualn.miniapp.module.points.PointsProblems.forbidden;

@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class PointsMaterialAdmission implements PointMaterialAdmission {
    private final PointsDependencies dependencies;
    public void begin(long user) {
        dependencies.begin(Action.CREATE,user);
        if(dependencies.actor(Action.CREATE)!=user) throw missing();
        if(!dependencies.eligible(user)) throw forbidden("ineligible");
    }
    public void finish(long user) {dependencies.finishAdmission(user);}
}
