package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.person.service.access.CurrentPersonAuthentication;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonAuthenticationBoundaryTest {
    @Test void legacyWildcardAndRootNameNeverAuthorizeNewCapabilities() {
        var tokens=mock(AdminTokenService.class);var owner=mock(TransactionalAuthorization.class);
        var auth=new CurrentPersonAuthentication(tokens,owner);var now=LocalDateTime.of(2026,10,18,0,0);
        when(tokens.requireAuthenticatedSession()).thenReturn(9L);when(tokens.requireAuthenticatedSessionIdentity()).thenReturn(9L);
        when(owner.currentNow(9)).thenReturn(new Decision(9,Source.LEGACY,"version",null,now,true,List.of("*"),List.of()));
        assertEquals(403,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
        when(owner.currentNow(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"version","catalog",now,true,List.of(),List.of(new Grant("id","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null))));
        assertEquals(403,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
        assertTrue(new RbacCatalog().supports("IDENTITY",Set.of("identity:read")));
    }
    @Test void ownerCapabilitiesStayBoundToCurrentRootAndCurrentSession() {
        var tokens=mock(AdminTokenService.class);var owner=mock(TransactionalAuthorization.class);
        var auth=new CurrentPersonAuthentication(tokens,owner);var now=LocalDateTime.of(2026,10,19,0,0);
        when(tokens.requireAuthenticatedSessionIdentity()).thenReturn(9L);
        var root=new Grant("root-id","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        var caps=List.of(new OwnerCapability("root-id","IDENTITY","GLOBAL",List.of("identity:read")));
        when(owner.currentNow(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"version","catalog",now,true,List.of(),List.of(root),caps));
        assertDoesNotThrow(()->auth.require(9,"IDENTITY","identity:read",now));
        when(owner.currentNow(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"version","catalog",now,true,List.of(),List.of(),caps));
        assertEquals(403,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
        when(tokens.requireAuthenticatedSessionIdentity()).thenReturn(10L);
        assertEquals(403,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
    }
    @Test void exactNamespaceAccountAndFreshSessionAreRequired() {
        var tokens=mock(AdminTokenService.class);var owner=mock(TransactionalAuthorization.class);var auth=new CurrentPersonAuthentication(tokens,owner);var now=LocalDateTime.of(2026,10,18,0,0);
        when(tokens.requireAuthenticatedSession()).thenReturn(9L);when(tokens.requireAuthenticatedSessionIdentity()).thenReturn(9L);
        when(owner.currentNow(9)).thenReturn(new Decision(9,Source.ASSIGNMENT,"version","catalog",now,true,List.of(),List.of(new Grant("id","synthetic","ORGANIZATION","GLOBAL",List.of("identity:read"),null))));
        assertEquals(403,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
        when(owner.currentNow(9)).thenThrow(PersonProblems.unavailable());
        assertEquals(503,assertThrows(ContractProblemException.class,()->auth.require(9,"IDENTITY","identity:read",now)).getStatus().value());
    }
}
