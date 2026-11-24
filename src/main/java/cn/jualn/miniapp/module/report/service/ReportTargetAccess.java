package cn.jualn.miniapp.module.report.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.content.service.GrowthManagementTargets;
import cn.jualn.miniapp.module.content.service.ReportTargetAuthorization;
import cn.jualn.miniapp.module.media.service.MediaEvidencePolicy;
import cn.jualn.miniapp.module.post.service.GrowthRecordAccess;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportTargetAccess implements ReportTargetAuthorization, MediaEvidencePolicy {
    private final ReportMapper reports;
    private final CurrentAuthorization authorization;
    private final GrowthManagementTargets targets;
    private final GrowthRecordAccess growth;

    private boolean allows(String type, long id, long actor, String... permissions) {
        if (!("post".equals(type) || "comment".equals(type)) || reports.selectAdminCase(type,id)==null) return false;
        var link=targets.link(type,id);
        return link==null ? authorization.current(actor).allowsWork("REPORT_HANDLE","REPORT",permissions)
                : growth.allowedAdmin(link.postId(),actor,"REPORT_HANDLE",permissions);
    }
    public boolean mayRead(String type,long id,long actor) { return allows(type,id,actor,"report:read"); }
    public boolean mayDispose(String type,long id,long actor) {
        return allows(type,id,actor,"report:read","report:handle","report:target-dispose");
    }
    public void requireDisposal(String type,long id,long actor) {
        if(!mayDispose(type,id,actor)) throw new BusinessException(ResultCode.FORBIDDEN);
        // Register final current-authorization revalidation with the existing transaction lease.
        if(!targets.require(type,id,actor,"REPORT_HANDLE","report:read","report:handle","report:target-dispose"))
            authorization.requireWork(actor,"REPORT_HANDLE","REPORT","report:read","report:handle","report:target-dispose");
    }
    public boolean mayReadEvidence(TargetType type,long id,long actor) {
        return type==TargetType.POST ? mayRead("post",id,actor)
                : type==TargetType.COMMENT && mayRead("comment",id,actor);
    }
}
