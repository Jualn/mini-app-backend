package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentAuthorizationTest {
    @Test void ordinaryManagementNeverConsultsLegacyWildcardOrAssignmentGrants() {
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        assertThrows(BusinessException.class,()->auth.requireLegacyEntry(9,"content:manage","POST","post:dispose"));
        verifyNoInteractions(reader);
    }
    @Test void accountGovernanceCannotInferPlatformFromGlobalOrCombineIndependentWitnesses() {
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,List.of(),List.of(
            new Grant("standalone","USER_GOVERNOR","USER","GLOBAL",List.of("user:read","user:manage"),null))));
        assertThrows(BusinessException.class,()->auth.requirePlatform(9,"user:manage","USER","USER_ACCOUNT_GOVERN","user:read","user:manage"));
        var target=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM",null,null);
        when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,List.of(),List.of(
            new Grant("read","USER_GOVERNOR","USER","GLOBAL",List.of("user:read"),null,"USER_ACCOUNT_GOVERN",target),
            new Grant("write","USER_GOVERNOR","USER","GLOBAL",List.of("user:manage"),null,"USER_ACCOUNT_GOVERN",target))));
        assertThrows(BusinessException.class,()->auth.requirePlatform(9,"user:manage","USER","USER_ACCOUNT_GOVERN","user:read","user:manage"));
        when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,List.of(),List.of(
            new Grant("complete","USER_GOVERNOR","USER","GLOBAL",List.of("user:read","user:manage"),null,"USER_ACCOUNT_GOVERN",target))));
        assertDoesNotThrow(()->auth.requirePlatform(9,"user:manage","USER","USER_ACCOUNT_GOVERN","user:read","user:manage"));
        when(reader.read(9)).thenReturn(decision(Source.LEGACY,List.of("*"),List.of()));
        assertDoesNotThrow(()->auth.requirePlatform(9,"user:manage","USER","USER_ACCOUNT_GOVERN","user:read","user:manage"));
    }

    @Test void mediaPrefilterDoesNotAuthorizeEditingOrAnotherNamespace() {
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        var d=decision(Source.ASSIGNMENT,List.of(),List.of(new Grant("a","MEDIA_EDITOR","ACTIVITY","GLOBAL",List.of("media:write"),null)));
        when(reader.read(9)).thenReturn(d);
        assertTrue(auth.candidates(d).contains("activity:edit"));
        assertFalse(auth.candidates(d).contains("public-event:edit"));
        assertDoesNotThrow(()->auth.require(9,"activity:edit","ACTIVITY","media:write"));
        assertThrows(BusinessException.class,()->auth.require(9,"activity:edit","ACTIVITY","activity:edit"));
        assertThrows(BusinessException.class,()->auth.require(9,"activity:edit","PUBLIC_EVENT","media:write"));
    }
    @Test void publishedEditCannotBorrowPublishFromAnotherDomain() {
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,List.of(),List.of(
                new Grant("a","editor","ACTIVITY","GLOBAL",List.of("activity:edit"),null),
                new Grant("b","publisher","PUBLIC_EVENT","GLOBAL",List.of("activity:publish"),null))));
        assertThrows(BusinessException.class,()->auth.require(9,"activity:edit","ACTIVITY","activity:edit","activity:publish"));
    }
    @Test void independentArticleGateStaysClosed() {
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,List.of(),List.of(
                new Grant("a","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:assignment-manage"),null),
                new Grant("b","CONTENT_OPERATOR","ARTICLE","GLOBAL",List.of("comment:dispose"),null))));
        assertThrows(BusinessException.class,()->auth.require(9,"content:manage","ARTICLE","comment:dispose"));
    }
    @Test void derivedPermissionsNeedTheirExactCurrentRootWitness() {
        var root=new Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        var owner=new OwnerCapability("root","USER","GLOBAL",List.of("user:read"));
        var d=new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(root),List.of(owner));
        assertTrue(d.allows("user:read","USER"));
        assertFalse(d.allows("user:read","POST"));
        assertFalse(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(),List.of(owner)).allows("user:read","USER"));
        assertFalse(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),false,List.of(),List.of(root),List.of(owner)).allows("user:read","USER"));
        assertFalse(new Decision(9,Source.LEGACY,"v","c",LocalDateTime.now(),true,List.of("*"),List.of(root),List.of(owner)).allows("user:read","USER"));
        assertFalse(new Decision(9,Source.ASSIGNMENT,"v","c",LocalDateTime.now(),true,List.of(),List.of(root),
                List.of(new OwnerCapability("other","USER","GLOBAL",List.of("user:read")))).allows("user:read","USER"));
        var reader=mock(RbacAuthorizationReader.class);var auth=new CurrentAuthorization(reader);
        assertTrue(auth.candidates(d).contains("user:read"));
        when(reader.read(9)).thenReturn(d);
        assertDoesNotThrow(()->auth.require(9,"user:read","USER","user:read"));
    }
    @Test void activityRegistrationReadRequiresItsOwnPlatformWitness() {
        var reader = mock(RbacAuthorizationReader.class);
        var auth = new CurrentAuthorization(reader);
        var permission = List.of("activity:registration:read");
        var platform = new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM", null, null);
        var organization = new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION", null, "1");
        for (String duty : List.of("ACTIVITY_REGISTRATION_READ", "ACTIVITY_REGISTRATION_EXPORT")) {
            when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT, List.of(), List.of(
                    new Grant("r", "reader", "ACTIVITY", "GLOBAL", permission, null, duty, platform))));
            assertDoesNotThrow(() -> auth.requireActivityRegistrationRead(9));
        }
        for (Grant unrelated : List.of(
                new Grant("r", "reader", "ACTIVITY", "GLOBAL", permission, null, "ACTIVITY_REGISTRATION_READ", organization),
                new Grant("d", "directory", "ACTIVITY", "GLOBAL", List.of("activity:read"), null))) {
            when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT, List.of(), List.of(unrelated)));
            assertThrows(BusinessException.class, () -> auth.requireActivityRegistrationRead(9));
        }
        when(reader.read(9)).thenReturn(decision(Source.LEGACY, permission, List.of()));
        assertThrows(BusinessException.class, () -> auth.requireActivityRegistrationRead(9));
    }

    private Decision decision(Source source,List<String> legacy,List<Grant> grants) {
        return new Decision(9,source,"v","c",LocalDateTime.now(),true,legacy,grants);
    }
}
