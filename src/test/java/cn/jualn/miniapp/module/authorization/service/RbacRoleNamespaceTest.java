package cn.jualn.miniapp.module.authorization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RbacRoleNamespaceTest {
    @Test void everyControlledRoleNamespaceUsesOnlyItsActualIntersection() throws Exception {
        var catalog=new RbacCatalog();
        try(var in=new ClassPathResource("rbac/rbac-v1.json").getInputStream()) {
            var artifact=new ObjectMapper().readTree(in);
            for(var role:artifact.get("roles")) for(var namespace:role.get("namespaces")) {
                var expected=new TreeSet<String>();
                for(var code:role.get("permissionCodes")) for(var permission:artifact.get("permissions"))
                    if(permission.get("code").equals(code)) for(var space:permission.get("namespaces"))
                        if(space.equals(namespace)) expected.add(code.asText());
                assertEquals(List.copyOf(expected),catalog.rolePermissions(role.get("code").asText(),namespace.asText()));
                assertFalse(expected.isEmpty());
            }
        }
        assertEquals(List.of("media:read"),catalog.rolePermissions("CONTENT_PUBLISHER","ACTIVITY"));
        assertEquals(List.of("media:read"),catalog.rolePermissions("CONTENT_PUBLISHER","PUBLIC_EVENT"));
        assertEquals(List.of("media:read","media:write"),catalog.rolePermissions("EVENT_OPERATOR","ARTICLE"));
        assertEquals(List.of("identity:map-manage","identity:read"),catalog.rolePermissions("PERSON_FACT_STEWARD_V1","IDENTITY"));
        assertEquals(List.of("organization:map-manage","organization:read"),catalog.rolePermissions("PERSON_FACT_STEWARD_V1","ORGANIZATION"));
        assertEquals(List.of("points:read"),catalog.rolePermissions("POINTS_READER_V1","POINTS"));
        assertTrue(catalog.rolePermissions("ROOT","POINTS").isEmpty());
        assertTrue(catalog.rolePermissions("UNKNOWN","IDENTITY").isEmpty());
        assertTrue(catalog.rolePermissions("LEARNING_MANAGER","LEARNING_TASK").contains("learning-task:revoke"));
    }
    @org.junit.jupiter.api.Test void rootManagementDoesNotPublishDisabledOrUnreadyCapabilities() {
        var catalog=new RbacCatalog();
        var root=new cn.jualn.miniapp.module.authorization.model.RbacModels.Grant("root","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null);
        var read=new cn.jualn.miniapp.module.authorization.model.RbacModels.Definition();
        read.setCode("points:read");read.setLifecycle("ENABLED");read.setReadiness("READY");
        var definitions=java.util.Map.of("points:read",read);
        assertTrue(catalog.ownerCapabilities(List.of(root),definitions).stream().anyMatch(c->c.namespace().equals("POINTS") && c.permissionCodes().contains("points:read")));
        read.setReadiness("NOT_READY");
        assertFalse(catalog.ownerCapabilities(List.of(root),definitions).stream().anyMatch(c->c.namespace().equals("POINTS")));
        read.setReadiness("READY");read.setLifecycle("DISABLED");
        assertFalse(catalog.ownerCapabilities(List.of(root),definitions).stream().anyMatch(c->c.namespace().equals("POINTS")));
        assertTrue(catalog.ownerCapabilities(List.of(),definitions).isEmpty());
    }
}
