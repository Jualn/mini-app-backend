package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.module.activity.controller.admin.AdminActivityController;
import cn.jualn.miniapp.module.activity.controller.admin.CanonicalAdminActivityLifecycleController;
import cn.jualn.miniapp.module.activity.controller.admin.AdminActivityRegistrationController;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AdminActivityRouteContractTest {
    @Test
    void managementControllerExposesOnlyCanonicalCrudMethods() {
        Set<String> methods = Arrays.stream(AdminActivityController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertThat(methods).containsExactlyInAnyOrder("list", "create", "get", "replace");
    }

    @Test
    void lifecycleControllerUsesFourExplicitColonActions() {
        Set<String> routes = Arrays.stream(CanonicalAdminActivityLifecycleController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class)).filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value())).collect(Collectors.toSet());
        assertThat(routes).containsExactlyInAnyOrder(
                "/v1/admin/activities/{activityId}:publish",
                "/v1/admin/activities/{activityId}:unpublish",
                "/v1/admin/activities/{activityId}:cancel",
                "/v1/admin/activities/{activityId}:end");
    }

    @Test
    void registrationManagementIsReadAndExportOnly() {
        Set<String> methods = Arrays.stream(AdminActivityRegistrationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertThat(methods).containsExactlyInAnyOrder("list", "export");
    }

    @Test
    void registrationExportAddsExplicitXlsxWithoutChangingCsvDefault() throws Exception {
        var method = AdminActivityRegistrationController.class.getDeclaredMethod(
                "export", Long.class, String.class,
                AdminActivityRegistrationController.ExportFormat.class);
        var mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping.value()).containsExactly("/export");
        assertThat(mapping.produces()).isEmpty();
        var format = method.getParameters()[2].getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        assertThat(format.defaultValue()).isEqualTo("CSV");
        assertThat(AdminActivityRegistrationController.ExportFormat.values())
                .containsExactly(AdminActivityRegistrationController.ExportFormat.CSV,
                        AdminActivityRegistrationController.ExportFormat.XLSX);
    }
}
