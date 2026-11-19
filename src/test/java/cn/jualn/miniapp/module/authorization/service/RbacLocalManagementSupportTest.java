package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RbacLocalManagementSupportTest {
    @Test void personOwnerProjectionRequiresRealNonExpiringRootAndDoesNotPublishOtherRoles() {
        var catalog=new RbacCatalog();
        var root=new RbacModels.Grant("root-id","ROOT","AUTHORIZATION","GLOBAL",java.util.List.of("authorization:root-manage"),null);
        assertTrue(catalog.ownerCapabilities(java.util.List.of(root),java.util.Map.of()).isEmpty());
        var definitions=new java.util.HashMap<String,RbacModels.Definition>();
        for(var code:RbacCatalog.PERSON_OWNER_PERMISSIONS){var row=new RbacModels.Definition();row.setCode(code);row.setLifecycle("ENABLED");row.setReadiness("READY");definitions.put(code,row);}
        var caps=catalog.ownerCapabilities(java.util.List.of(root),definitions);
        assertEquals(RbacCatalog.PERSON_OWNER_PERMISSIONS,caps.stream().flatMap(c->c.permissionCodes().stream()).collect(java.util.stream.Collectors.toSet()));
        var now=java.time.LocalDateTime.now();
        var decision=new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","c",now,true,java.util.List.of(),java.util.List.of(root),caps);
        assertTrue(decision.allows("identity:map-manage","IDENTITY"));
        assertTrue(decision.allows("person-scope:manage","ORGANIZATION"));
        assertFalse(decision.allows("points:review","POINTS"));
        assertFalse(decision.allows("identity:read","ORGANIZATION"));
        assertTrue(catalog.supports("IDENTITY",Set.of("identity:read")));
        assertTrue(catalog.ownerCapabilities(java.util.List.of(),java.util.Map.of()).isEmpty());
        var expiring=new RbacModels.Grant("root-id","ROOT","AUTHORIZATION","GLOBAL",root.permissionCodes(),now.plusDays(1));
        assertTrue(catalog.ownerCapabilities(java.util.List.of(expiring),java.util.Map.of()).isEmpty());
        var blocked=new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","c",now,false,java.util.List.of(),java.util.List.of(root),caps);
        assertFalse(blocked.allows("identity:read","IDENTITY"));
    }

    @Test void implementedModulesAreSupportedWithoutPublishingDefinitions() {
        var catalog=new RbacCatalog();
        assertTrue(catalog.supports("USER",Set.of("user:read","user:manage")));
        assertTrue(catalog.supports("IDENTITY",Set.of("identity:read","identity:map-manage")));
        assertTrue(catalog.supports("ORGANIZATION",Set.of("organization:read","organization:map-manage")));
        assertTrue(catalog.supports("ORGANIZATION",Set.of("person-scope:manage")));
        assertTrue(catalog.supports("ARTICLE",Set.of("article:read","article:edit","article-category:manage")));
        assertTrue(catalog.supports("ACTIVITY",Set.of("activity:read","activity:publish")));
        assertTrue(catalog.supports("LEARNING_TASK",Set.of("learning-task:read","learning-submission:review")));
        assertTrue(catalog.supports("POINTS",Set.of("points:review")));
        assertFalse(catalog.supports("POST",Set.of("post:restore")));
        assertFalse(catalog.supports("USER",Set.of("user:role")));
        assertFalse(catalog.supports("SYSTEM",Set.of("system:configure")));
        assertFalse(catalog.supports("USER",Set.of("article:read")));
    }
    @Test void implementedSupportDoesNotDependOnProfileDatabaseOrRetiredSwitch() {
        for (String profile : java.util.List.of("dev", "prod", "test")) {
            var environment = new MockEnvironment()
                    .withProperty("admin-auth.local-management.enabled", "false")
                    .withProperty("spring.datasource.url", "jdbc:mysql://remote.internal:3306/campus");
            environment.setActiveProfiles(profile);
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withInitializer(context -> context.setEnvironment(environment))
                    .withBean(RbacCatalog.class).run(context -> {
            assertNull(context.getStartupFailure());
            var catalog = context.getBean(RbacCatalog.class);
            assertTrue(catalog.supports("USER", Set.of("user:read")));
            assertTrue(catalog.supports("POINTS", Set.of("points:review")));
            assertFalse(catalog.supports("POINTS", Set.of("points:rule-manage")));
            assertFalse(catalog.supports("SYSTEM", Set.of("system:configure")));
                    });
        }
    }
}
