package cn.jualn.miniapp.module.audit.service.impl;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.bo.AdminReviewDecisionBO;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.activity.service.ActivityReviewService;
import cn.jualn.miniapp.module.comment.service.CommentGovernanceService;
import cn.jualn.miniapp.module.media.TestMediaService;
import cn.jualn.miniapp.module.post.service.PostGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminReviewAuthorizationTest {
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final ContentAuditLogMapper logs=mock(ContentAuditLogMapper.class);
    final CommentGovernanceService comments=mock(CommentGovernanceService.class);
    final AdminReviewServiceImpl service=new AdminReviewServiceImpl(new CurrentAuthorization(reader),logs,
            mock(PostGovernanceService.class),comments,mock(ActivityReviewService.class),mock(TestMediaService.class),new ObjectMapper());

    final cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService rounds=mock(cn.jualn.miniapp.module.audit.service.ContentSafetyReviewService.class);
    @org.junit.jupiter.api.BeforeEach void wire() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"growth",mock(cn.jualn.miniapp.module.content.service.GrowthManagementTargets.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"growthAccess",mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"managementAudit",mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"reviews",rounds);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"reviewAccess",new cn.jualn.miniapp.module.audit.service.ContentSafetyAuthorization(new CurrentAuthorization(reader)));
    }
    void grants(String space,String... codes) {
        when(reader.read(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),
                List.of(new Grant("a","test",space,"GLOBAL",List.of(codes),null,List.of("POST","ACTIVITY","PUBLIC_EVENT").contains(space)?cn.jualn.miniapp.module.audit.service.ContentSafetyAuthorization.duty(space):"UNSUPPORTED_REVIEW",new UnifiedModels.Target("PLATFORM",null,null)))));
    }
    AdminReviewDecisionBO command(int scene) {
        if(scene==4) {
            var r=new cn.jualn.miniapp.module.audit.entity.ContentSafetyReview();r.setTaskId(7L);r.setTargetType(4);r.setTargetId(21L);r.setResourceNamespace("PUBLIC_EVENT");r.setState("pending");
            when(rounds.find(7L)).thenReturn(r);when(rounds.lock(7L)).thenReturn(r);
            when(rounds.evidence(r)).thenReturn(new cn.jualn.miniapp.module.audit.bo.SafetyReviewProjection.Evidence("version",true,List.of(new cn.jualn.miniapp.module.audit.bo.SafetyReviewProjection.Check("1","text:content",true,"succeeded","review","wechat",null,null,null))));
        } else when(rounds.find(7L)).thenReturn(null);
        when(logs.selectRiskTaskForUpdate(7L)).thenReturn(ContentAuditLog.builder().targetType(scene).targetId(21L).build());
        return AdminReviewDecisionBO.builder().operatorId(9L).taskId(7L).idempotencyKey("review-test-000001").action("approve").build();
    }
    @Test void decisionNeedsReadAndDecideInTheSamePersistentNamespace() {
        var command=command(4);when(comments.managementNamespace(21L)).thenReturn("ACTIVITY");
        grants("POST","review:read","review:decide");
        assertThrows(BusinessException.class,()->service.decide(command));
        grants("ACTIVITY","review:decide");
        assertThrows(BusinessException.class,()->service.decide(command));
        grants("ACTIVITY","review:read");
        assertThrows(BusinessException.class,()->service.decide(command));
        verify(logs,never()).insert(any(ContentAuditLog.class));verify(comments,never()).approveCommentReview(any(),any(),any());
    }
    @Test void reviewDecisionDoesNotRequireDispositionButInvokesTheOwner() {
        var command=command(4);when(comments.managementNamespace(21L)).thenReturn("PUBLIC_EVENT");
        grants("PUBLIC_EVENT","review:read","review:decide");
        when(logs.selectReviewNamespace(7L)).thenReturn("PUBLIC_EVENT");
        when(logs.selectRepresentativeRiskTaskId(4,21L)).thenReturn(7L);
        service.decide(command);
        verify(comments).approveCommentReview(21L,9L,null);verify(logs).insert(any(ContentAuditLog.class));
    }
    @Test void userProfileScenesCannotBorrowAccountGovernanceOrContentReview() {
        for(int scene:List.of(5,6,7,8)) {
            var command=command(scene);grants("USER","user:read","user:manage","review:read","review:decide");
            assertThrows(BusinessException.class,()->service.decide(command));
        }
        verify(logs,never()).insert(any(ContentAuditLog.class));
    }
    @Test void unknownSceneAndArticleCommentStayClosed() {
        for(int scene:List.of(99,11,3,9,10)) assertThrows(BusinessException.class,()->service.decide(command(scene)));
        var command=command(4);rounds.find(7L).setResourceNamespace("ARTICLE");when(comments.managementNamespace(21L)).thenReturn("ARTICLE");
        grants("ARTICLE","review:read","review:decide");
        assertThrows(BusinessException.class,()->service.decide(command));
        when(reader.read(9)).thenReturn(new Decision(9,Source.LEGACY,"v","c",LocalDateTime.now(),true,List.of("*"),List.of()));
        assertThrows(BusinessException.class,()->service.decide(command));
        verify(logs,never()).insert(any(ContentAuditLog.class));
    }
}
