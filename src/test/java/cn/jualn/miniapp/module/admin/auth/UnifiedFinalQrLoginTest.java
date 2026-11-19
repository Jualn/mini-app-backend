package cn.jualn.miniapp.module.admin.auth;

import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.config.AdminQrLoginProperties;
import cn.jualn.miniapp.module.admin.auth.service.*;
import cn.jualn.miniapp.module.admin.auth.service.impl.AdminQrLoginServiceImpl;
import cn.jualn.miniapp.module.admin.auth.store.AdminQrLoginStore;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedFinalQrLoginTest {
    final AdminQrLoginStore store=mock(AdminQrLoginStore.class);
    final AdminTokenService tokens=mock(AdminTokenService.class);
    final cn.jualn.miniapp.module.user.service.UserService users=mock(cn.jualn.miniapp.module.user.service.UserService.class);
    final CurrentAuthorization authorization=mock(CurrentAuthorization.class);
    final AdminQrLoginServiceImpl service=new AdminQrLoginServiceImpl(store,tokens,users,new AdminPermissionPolicy(),new AdminQrLoginProperties(),mock(cn.jualn.miniapp.third.wx.client.WxClient.class),new SimpleMeterRegistry(),authorization);
    final String secret="a".repeat(43),scene="a".repeat(32);
    final AdminQrCandidateBO candidate=new AdminQrCandidateBO("same-token","hash","{}",90000);
    Decision duty(){return new Decision(7,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(),List.of(),true);}
    AdminQrLoginStore.Snapshot state(cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status status){return new AdminQrLoginStore.Snapshot(new AdminQrSessionBO("session",status,80000,1500,1L,status==CONSUMED?2L:null),7L,candidate,1000,"REPLAY",null);}
    @Test void explicitLegacyRejectedBeforeAnyStoreMutation(){
        assertEquals(400,assertThrows(ContractProblemException.class,()->service.consumeSession("session",secret,"local","LEGACY")).getStatus().value());verifyNoInteractions(store,tokens,users,authorization);
        assertTrue(new AdminPermissionPolicy().permissions(cn.jualn.miniapp.module.user.enums.UserRole.ADMIN).isEmpty());
        assertFalse(new Decision(7,Source.LEGACY,"v",null,LocalDateTime.now(),true,List.of("*"),List.of()).canUseAdmin());
    }
    @Test void omittedRepresentationReplaysAssignmentAndOriginalTokenWithFreshAdmission(){
        when(authorization.current(7)).thenReturn(duty());when(users.getUserProfile(7L)).thenReturn(new UserProfileBO());
        when(store.web(eq("read"),eq("session"),anyString(),isNull(),isNull())).thenReturn(state(CONSUMED));
        var result=new AdminQrLoginResultBO("same-token",new AdminQrIdentityBO("7","user","u",null,null,null,"ASSIGNMENT"), candidate.tokenExpiresAt());
        when(tokens.qrResult(candidate)).thenReturn(result);when(tokens.validQrResult(7,"session",candidate,1000)).thenReturn(true);
        assertSame(result,service.consumeSession("session",secret,"local"));verify(authorization,atLeast(3)).current(7);verify(tokens).validQrResult(7,"session",candidate,1000);verify(tokens,never()).materializeQrCandidate(anyLong(),any(),anyLong());
    }
    @Test void consumedConfirmationDoesNotBypassRevokedAdmissionOrOriginalToken(){
        when(store.mobile(eq("inspect"),anyString(),eq(7L))).thenReturn(state(CONSUMED));
        when(authorization.current(7)).thenReturn(duty());when(tokens.validQrResult(7,"session",candidate,1000)).thenReturn(false);
        assertEquals(403,assertThrows(ContractProblemException.class,()->service.confirmSession(scene,7,"local")).getStatus().value());
        when(authorization.current(7)).thenReturn(new Decision(7,Source.ASSIGNMENT,"v2","c",LocalDateTime.now(),true,List.of(),List.of()));
        assertEquals(403,assertThrows(ContractProblemException.class,()->service.confirmSession(scene,7,"local")).getStatus().value());
        verify(store,never()).mobile(eq("confirm"),anyString(),anyLong());
    }
}
