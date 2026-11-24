package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.authorization.mapper.*;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.points.mapper.*;
import cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy;
import cn.jualn.miniapp.module.learningtask.service.*;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.user.service.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Owning policy decisions with explicit primary-reader doubles; not DB/session/Provider proof. */
class RootManagementPolicyTest {
    final LocalDateTime at=LocalDateTime.of(2026,11,1,0,0);
    final Grant root=new Grant("root-source","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
    Decision owner(String space,String...permissions) {
        return new Decision(9,Source.ASSIGNMENT,"v","c",at,true,List.of(),List.of(root),
                List.of(new OwnerCapability(root.assignmentId(),space,"GLOBAL",List.of(permissions))));
    }
    UnifiedDutyReader reader(PersonBusinessScopes persons) {
        var catalog=new RbacCatalog();
        return new UnifiedDutyReader(mock(UnifiedMapper.class),mock(RbacMapper.class),catalog,new UnifiedDutyCatalog(catalog),persons);
    }
    @Test void rootPlatformOperationsNeedCurrentCompleteCapabilityNotNameOrStitchedPermissions() {
        var d=owner("POST","review:read","review:decide");
        assertTrue(d.allowsWork("POST_SAFETY_REVIEW","POST","review:read","review:decide"));
        assertFalse(d.allowsWork("POST_SAFETY_REVIEW","ACTIVITY","review:read"));
        assertFalse(d.allowsWork("POST_SAFETY_REVIEW","POST","unpublished:new"));
        var split=new Decision(9,Source.ASSIGNMENT,"v","c",at,true,List.of(),List.of(root),List.of(
            new OwnerCapability(root.assignmentId(),"POST","GLOBAL",List.of("review:read")),
            new OwnerCapability(root.assignmentId(),"POST","GLOBAL",List.of("review:decide"))));
        assertFalse(split.allowsWork("POST_SAFETY_REVIEW","POST","review:read","review:decide"));
        var revoked=new Decision(9,Source.ASSIGNMENT,"v2","c",at,true,List.of(),List.of(),d.ownerCapabilities());
        assertFalse(revoked.allowsWork("POST_SAFETY_REVIEW","POST","review:read"));
        var legacy=new Decision(9,Source.LEGACY,"v","c",at,true,List.of("*"),List.of(root),d.ownerCapabilities());
        assertNull(legacy.managementOwner("POST","review:read"));
        var blocked=new Decision(9,Source.ASSIGNMENT,"v","c",at,false,List.of(),List.of(root),d.ownerCapabilities());
        assertNull(blocked.managementOwner("POST","review:read"));
    }
    @Test void pointsUsesRealRootAssignmentWithoutInventingScopeAndRevocationEndsGlobalRange() {
        var persons=mock(PersonBusinessScopes.class);var duties=reader(persons);var auth=mock(TransactionalAuthorization.class);
        var catalog=new UnifiedDutyCatalog(new RbacCatalog());var template=catalog.template("POINTS_REVIEW");
        when(auth.current(9,at)).thenReturn(owner("POINTS","points:read"));
        var policy=new PointsAcceptancePolicy(mock(PointsMapper.class),auth,mock(PersonFactReaders.class),persons,duties,mock(PointsR3Mapper.class));
        var sources=policy.sources(9,"POINTS_REVIEW",at);
        assertEquals(1,sources.size());assertNull(sources.get(0).scopeGrantId());
        assertEquals(root.assignmentId(),sources.get(0).assignmentId());
        assertTrue(policy.organizationAllowed(9,"POINTS_REVIEW","any-real-target",at));
        when(auth.current(9,at)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v2","c",at,true,List.of(),List.of()));
        assertTrue(policy.sources(9,"POINTS_REVIEW",at).isEmpty());
        verify(persons,never()).grant(any());
    }
    @Test void rootPointsReversalNeedsExactOpenedPermissionsAndRetainsLedgerAndFallbackGuards() {
        var facts=mock(PersonFactReaders.class);var authorization=mock(TransactionalAuthorization.class);
        var context=mock(cn.jualn.miniapp.module.person.service.facts.PersonReadContext.class);
        when(context.evaluatedAt()).thenReturn(at);
        var acceptance=mock(PointsAcceptancePolicy.class);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(acceptance);when(provider.getObject()).thenReturn(acceptance);
        var deps=new cn.jualn.miniapp.module.points.service.CurrentPointsDependencies(mock(CurrentAuthorization.class),new RbacCatalog(),mock(UserAccountService.class),mock(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class),facts,mock(cn.jualn.miniapp.module.person.service.facts.PersonCatalog.class),authorization,mock(org.springframework.transaction.PlatformTransactionManager.class),mock(cn.jualn.miniapp.module.points.service.PointsResultNotifications.class),mock(PointsMapper.class),mock(cn.jualn.miniapp.module.points.service.PointsPrivateMaterials.class),mock(UserCurrentLoginAccess.class));
        org.springframework.test.util.ReflectionTestUtils.setField(deps,"acceptance",provider);
        Object resource=org.springframework.test.util.ReflectionTestUtils.getField(cn.jualn.miniapp.module.points.service.CurrentPointsDependencies.class,"RESOURCE");
        TransactionSynchronizationManager.bindResource(resource,context);
        try {
            when(authorization.current(9,at)).thenReturn(owner("POINTS","points:read","points:adjust","points:adjust-approve"));
            deps.requirePersonManage(9,"person",cn.jualn.miniapp.module.points.bo.PointsBO.Action.REVERSE);
            verify(acceptance).ledger(9,"person",at);
            verify(facts,never()).authorizePerson(anyLong(),anyString(),any(),any(),any());
            when(authorization.current(9,at)).thenReturn(owner("POINTS","points:read"));
            when(facts.authorizePerson(anyLong(),anyString(),any(),any(),any())).thenReturn(new PersonFactReaders.ScopeDecision(context,PersonFactReaders.Decision.DENY,"NO_EFFECTIVE_SCOPE"));
            assertEquals(404,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->deps.requirePersonManage(9,"person",cn.jualn.miniapp.module.points.bo.PointsBO.Action.REVERSE)).getStatus().value());
        } finally {TransactionSynchronizationManager.unbindResource(resource);}
    }
    @Test void learningRootReadsAcrossTargetsButMalformedFactsAndFinalRevocationStillDeny() {
        var persons=mock(PersonBusinessScopes.class);var duties=reader(persons);var auth=mock(TransactionalAuthorization.class);
        var mapper=mock(LearningTaskMapper.class);
        var one=new LearningTask();one.setId(1L);one.setManagementTarget("{\"type\":\"ORGANIZATION\",\"organizationId\":\"A\"}");
        var two=new LearningTask();two.setId(2L);two.setManagementTarget("{\"type\":\"ORGANIZATION\",\"organizationId\":\"B\"}");
        when(mapper.selectById(1L)).thenReturn(one);when(mapper.selectById(2L)).thenReturn(two);when(mapper.selectList(null)).thenReturn(List.of(one,two));
        when(auth.currentNow(9)).thenReturn(owner("LEARNING_TASK","learning-task:read","learning-task:edit"));
        var policy=new LearningTaskScopePolicy(persons,auth,duties,mapper,mock(UserLearningTaskRecipientService.class),mock(UserCurrentLoginAccess.class),mock(LearningTaskResponsibilities.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.initSynchronization();
        try {
            policy.creation(9,new Target("PLATFORM",null,null));
            policy.creation(9,new Target("ORGANIZATION",null,"A"));
            policy.task(9,Action.READ,1L);policy.task(9,Action.READ,2L);
            assertEquals(List.of(1L,2L),policy.readable(9,false).taskIds());
            two.setManagementTarget("{\"type\":\"UNKNOWN\"}");
            assertEquals(503,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.task(9,Action.READ,2L)).getStatus().value());
            two.setManagementTarget("{\"type\":\"ORGANIZATION\",\"organizationId\":\"B\"}");
            when(auth.currentNow(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v2","c",at,true,List.of(),List.of()));
            assertEquals(404,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.finish(9,false)).getStatus().value());
            assertEquals(404,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.task(9,Action.READ,1L)).getStatus().value());
        } finally {
            for(var sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        }
    }
}
