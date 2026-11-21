package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

class UnifiedR3PolicyTest {
    @Test void everyRegisteredTemplateHasAnOwnerButMissingRuntimeDefinitionsRemainClosed() {
        var catalog=new RbacCatalog();var duties=new UnifiedDutyCatalog(catalog);
        assertEquals(42,duties.templates().size());assertTrue(duties.templates().stream().allMatch(t->duties.supported(t.dutyCode())));
        for(var t:duties.templates()) if(PlatformDutyPolicy.supports(t.dutyCode())) {
            var valid=new UnifiedModels.Selection(t.dutyCode(),new UnifiedModels.Target("PLATFORM",null,null),"2027-01-01T00:00:00Z",null);
            assertTrue(PlatformDutyPolicy.matches(t,valid),t.dutyCode());
            assertFalse(PlatformDutyPolicy.matches(t,new UnifiedModels.Selection(t.dutyCode(),new UnifiedModels.Target("GLOBAL",null,null),valid.expiresAt(),null)));
            assertEquals(Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(t.dutyCode()),PlatformDutyPolicy.matches(t,new UnifiedModels.Selection(t.dutyCode(),new UnifiedModels.Target("ORGANIZATION",null,"a"),valid.expiresAt(),null)));
            assertFalse(PlatformDutyPolicy.matches(t,new UnifiedModels.Selection(t.dutyCode(),valid.target(),valid.expiresAt(),List.of(valid.target()))));
            assertFalse(duties.ready(t.dutyCode(),List.of(),List.of()));
        }
        assertNotEquals(catalog.digest(),duties.runtimeVersion(catalog.digest()));
        assertNotEquals(duties.runtimeVersion("old"),duties.runtimeVersion("new"));
    }
    @Test void runtimePublicationEnablesEverySupportedDutyButRequiresTheWholeRoleAndEveryGrantableMember() {
        var rbac=new RbacCatalog();var duties=new UnifiedDutyCatalog(rbac);
        for(var template:duties.templates()) {
            var role=readyDefinition(template.roleCode());
            var permissions=rbac.role(template.roleCode()).permissions().stream().map(UnifiedR3PolicyTest::readyDefinition).toList();
            assertTrue(duties.ready(template.dutyCode(),permissions,List.of(role)),template.dutyCode());
            role.setGrantable(false);
            assertFalse(duties.ready(template.dutyCode(),permissions,List.of(role)),template.dutyCode());
            role.setGrantable(true);
            for(var member:permissions) {
                member.setReadiness("NOT_READY");
                assertFalse(duties.ready(template.dutyCode(),permissions,List.of(role)),template.dutyCode()+" "+member.getCode());
                member.setReadiness("READY");member.setGrantable(false);
                assertFalse(duties.ready(template.dutyCode(),permissions,List.of(role)),template.dutyCode()+" "+member.getCode());
                member.setGrantable(true);
            }
        }
    }
    @Test void homeRequiresItsOwnPlatformDutyOrAnEffectiveOwnerAndDistinguishesReadiness() {
        var reader = org.mockito.Mockito.mock(RbacAuthorizationReader.class);
        var authorization = new CurrentAuthorization(reader);
        String duty = "HOME_BANNER_MANAGE", namespace = "HOME", permission = "home-banner:manage";
        var platform = new UnifiedModels.Target("PLATFORM", null, null);
        var scoped = new Grant("scoped", "HOME_BANNER_MANAGER_V1", namespace, "GLOBAL", List.of(permission), null, duty, platform);
        var standalone = new Grant("standalone", "HOME_BANNER_MANAGER_V1", namespace, "GLOBAL", List.of(permission), null);
        var unrelated = new Grant("activity", "EVENT_OPERATOR", "ACTIVITY", "GLOBAL", List.of("activity:read"), null);
        var blocked = new Decision(9, Source.ASSIGNMENT, "v", "c", LocalDateTime.now(), true, List.of(), List.of(standalone, unrelated));
        org.mockito.Mockito.when(reader.read(9)).thenReturn(blocked);
        var unavailable = assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                () -> authorization.requirePublishedPlatformDuty(9, duty, namespace, permission));
        assertEquals(503, unavailable.getStatus().value());

        org.mockito.Mockito.when(reader.platformCapabilityReady(duty, namespace, permission, blocked)).thenReturn(true);
        assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> authorization.requirePublishedPlatformDuty(9, duty, namespace, permission));

        var permitted = new Decision(9, Source.ASSIGNMENT, "v", "c", LocalDateTime.now(), true, List.of(), List.of(scoped));
        org.mockito.Mockito.when(reader.read(9)).thenReturn(permitted);
        assertDoesNotThrow(() -> authorization.requirePublishedPlatformDuty(9, duty, namespace, permission));

        var root = new Grant("root", "ROOT", "AUTHORIZATION", "GLOBAL", List.of("authorization:root-manage"), null);
        var owner = new OwnerCapability("root", namespace, "GLOBAL", List.of(permission));
        org.mockito.Mockito.when(reader.read(9)).thenReturn(new Decision(9, Source.ASSIGNMENT, "v", "c",
                LocalDateTime.now(), true, List.of(), List.of(root), List.of(owner)));
        assertDoesNotThrow(() -> authorization.requirePublishedPlatformDuty(9, duty, namespace, permission));
    }

    private static Definition readyDefinition(String code) {
        var row=new Definition();row.setCode(code);row.setLifecycle("ENABLED");row.setReadiness("READY");row.setGrantable(true);return row;
    }
    @Test void sameAuthorityCannotJoinDifferentGrantWitnessesAndStandaloneCannotAuthorizeNewStatusAction() {
        var now=LocalDateTime.now();var target=new UnifiedModels.Target("PLATFORM",null,null);
        var read=new Grant("s1","CONTENT_REVIEWER","POST","GLOBAL",List.of("review:read"),null,"POST_SAFETY_REVIEW",target);
        var decide=new Grant("s2","CONTENT_REVIEWER","POST","GLOBAL",List.of("review:decide"),null,"POST_SAFETY_REVIEW",target);
        var joined=new Decision(9,Source.ASSIGNMENT,"v","c",now,true,List.of(),List.of(read,decide));
        assertFalse(joined.allowsAll("POST","review:read","review:decide"));
        var complete=new Grant("s3","CONTENT_REVIEWER","POST","GLOBAL",List.of("review:read","review:decide"),null,"POST_SAFETY_REVIEW",target);
        assertTrue(new Decision(9,Source.ASSIGNMENT,"v","c",now,true,List.of(),List.of(complete)).allowsAll("POST","review:read","review:decide"));
        var old=new Grant("old","CONTENT_PUBLISHER","OFFICIAL_CONTENT","GLOBAL",List.of("official-content:publish"),null);
        assertFalse(new Decision(9,Source.ASSIGNMENT,"v","c",now,true,List.of(),List.of(old)).allowsWork("OFFICIAL_CONTENT_PUBLISH","OFFICIAL_CONTENT","official-content:publish"));
        assertFalse(joined.allowsWork("ACTIVITY_SAFETY_REVIEW","ACTIVITY","review:decide"));
    }
}
