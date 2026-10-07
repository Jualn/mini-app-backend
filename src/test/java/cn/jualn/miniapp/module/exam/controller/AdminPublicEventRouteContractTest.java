package cn.jualn.miniapp.module.exam.controller;

import cn.jualn.miniapp.module.exam.controller.admin.AdminPublicEventController;
import cn.jualn.miniapp.module.exam.controller.admin.CanonicalAdminPublicEventLifecycleController;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPublicEventRouteContractTest {
    @Test
    void managementControllerExposesOnlyCanonicalCrudMethods() {
        Set<String> methods = Arrays.stream(AdminPublicEventController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertThat(methods).containsExactlyInAnyOrder("list", "create", "get", "replace");
    }

    @Test
    void lifecycleControllerUsesFourExplicitColonActions() {
        Set<String> routes = Arrays.stream(CanonicalAdminPublicEventLifecycleController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class)).filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())).collect(Collectors.toSet());
        assertThat(routes).containsExactlyInAnyOrder(
                "/v1/admin/public-events/{publicEventId}:publish",
                "/v1/admin/public-events/{publicEventId}:unpublish",
                "/v1/admin/public-events/{publicEventId}:cancel",
                "/v1/admin/public-events/{publicEventId}:end");
    }
}
