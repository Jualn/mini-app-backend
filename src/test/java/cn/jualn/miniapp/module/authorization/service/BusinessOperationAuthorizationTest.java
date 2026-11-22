package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.DutyOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Grant;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.content.service.GrowthManagementTargets;
import cn.jualn.miniapp.module.post.service.GrowthRecordAccess;
import cn.jualn.miniapp.module.report.mapper.AdminReportCaseRow;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import cn.jualn.miniapp.module.report.service.ReportTargetAccess;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOperationAuthorizationTest {
    private Grant grant(String duty,String namespace,Target target,String... permissions) {
        return new Grant(duty,"role",namespace,"GLOBAL",List.of(permissions),null,duty,target);
    }
    private Decision decision(Grant... grants) {
        return new Decision(9,Source.ASSIGNMENT,"a","c",LocalDateTime.now(),true,List.of(),List.of(grants));
    }
    @Test void publicationOperationIncludesNecessaryReadButCannotEdit() {
        var d=decision(grant("ACTIVITY_PUBLICATION_MANAGE","ACTIVITY",new Target("PLATFORM",null,null),"activity:read","activity:publish","activity:unpublish","media:read"));
        assertTrue(d.allowsWork("ACTIVITY_MANAGE","ACTIVITY","activity:read","activity:publish"));
        assertTrue(d.allowsWork("ACTIVITY_MANAGE","ACTIVITY","activity:unpublish"));
        assertFalse(d.allowsWork("ACTIVITY_MANAGE","ACTIVITY","activity:edit"));
        assertFalse(d.allowsWork("PUBLIC_EVENT_MANAGE","PUBLIC_EVENT","activity:publish"));
    }
    @Test void independentWitnessesCannotBeCombinedToCompleteAnOperation() {
        var target=new Target("PLATFORM",null,null);
        var d=decision(grant("POST_READ","POST",target,"content:read"),grant("POST_DISPOSE","POST",target,"post:dispose"));
        assertFalse(d.allowsWork("POST_MODERATE","POST","content:read","post:dispose"));
    }
    @Test void organizationOperationsNeverBecomeOrdinaryPlatformContentGrants() {
        var d=decision(grant("POST_DISPOSE","POST",new Target("ORGANIZATION",null,"a"),"content:read","post:dispose"));
        assertFalse(d.allows("content:read","POST"));
        assertFalse(d.allowsWork("POST_MODERATE","POST","post:dispose"));
    }
    @Test void readonlyOperationNeverAcquiresSiblingWrites() {
        var d=decision(grant("REPORT_READ","REPORT",new Target("PLATFORM",null,null),"report:read"));
        assertTrue(d.allowsWork("REPORT_HANDLE","REPORT","report:read"));
        assertFalse(d.allowsWork("REPORT_HANDLE","REPORT","report:handle"));
        assertFalse(d.allowsWork("REPORT_HANDLE","REPORT","report:target-dispose"));
    }
    @Test void reportEvidenceAndDisposalAreLimitedToAnExistingCaseAndItsOwnGrant() {
        var reports=mock(ReportMapper.class);
        var authorization=mock(CurrentAuthorization.class);
        var targets=mock(GrowthManagementTargets.class);
        var access=new ReportTargetAccess(reports,authorization,targets,mock(GrowthRecordAccess.class));
        var target=new Target("PLATFORM",null,null);
        when(reports.selectAdminCase("post",7L)).thenReturn(new AdminReportCaseRow());
        when(authorization.current(9)).thenReturn(decision(grant("REPORT_READ","REPORT",target,"report:read")));
        assertTrue(access.mayRead("post",7,9));
        assertFalse(access.mayDispose("post",7,9));
        when(authorization.current(9)).thenReturn(decision(grant("REPORT_RESOLVE","REPORT",target,"report:read","report:handle","report:target-dispose")));
        assertTrue(access.mayDispose("post",7,9));
        assertFalse(access.mayRead("post",8,9));
        assertFalse(access.mayDispose("post",8,9));
        assertFalse(access.mayRead("article",7,9));
    }
    @Test void everyIndependentOperationHasARegisteredOwningPolicy() throws Exception {
        var duties=new UnifiedDutyCatalog(new RbacCatalog());
        try(var in=getClass().getResourceAsStream("/rbac/duty-templates-v1.json")) {
            var catalog=new ObjectMapper().readTree(in);
            for(var operation:catalog.required("templates")) {
                if(!operation.has("operationOf")) continue;
                String code=operation.required("dutyCode").asText();
                assertTrue(duties.supported(code),code);
                assertTrue(DutyOperations.matches(operation.required("operationOf").asText(),code),code);
            }
        }
    }

}
