package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCatalog;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Grant;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LearningTaskAuthorizationPolicyTest {
    final CurrentAuthorization reader = mock(CurrentAuthorization.class);
    final RbacCatalog catalog = mock(RbacCatalog.class);
    final LearningTaskAuthorizationPolicy policy = new LearningTaskAuthorizationPolicy(reader,catalog,mock(UserAccountService.class));

    Decision decision(Source source, boolean eligible, List<Grant> grants) {
        return new Decision(9,source,"version","catalog",LocalDateTime.now(),eligible,List.of("*","content:manage"),grants);
    }
    Grant grant(String namespace, String scope, String... permissions) {
        return new Grant("fixture","fixture",namespace,scope,List.of(permissions),null);
    }
    void ready(Grant... grants) {
        when(catalog.supports(eq("LEARNING_TASK"),any())).thenReturn(true);
        when(reader.current(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of(grants)));
    }
    @Test void implementedManagementSupportIsAvailableByDefault() {
        var production = new RbacCatalog();
        for (Action action : Action.values()) {
            if (Set.of(Action.MY_READ,Action.CONFIRM,Action.SUBMIT).contains(action)) { continue; }
            assertEquals(action != Action.REVOKE_PARTICIPATED, production.supports("LEARNING_TASK",LearningTaskAuthorizationPolicy.permissions(action)));
        }
    }

    @Test void legacyWildcardAndContentPermissionsCannotAuthorize() {
        when(reader.current(9)).thenReturn(decision(Source.LEGACY,true,List.of()));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.CREATE));
    }
    @Test void catalogNotReleasedIsCanonicalDependency503EvenWithSyntheticGrant() {
        when(reader.current(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of(grant("LEARNING_TASK","GLOBAL","learning-task:edit"))));
        var problem = assertThrows(ContractProblemException.class,() -> policy.requireAdmin(9,Action.CREATE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,problem.getStatus());
        assertEquals("AUTHORIZATION",problem.getMessage());
    }
    @Test void allMappedOperationsRequireExactNamespaceAndPermission() {
        for (Action action : Action.values()) {
            if (Set.of(Action.MY_READ,Action.CONFIRM,Action.SUBMIT).contains(action)) { continue; }
            var permissions = LearningTaskAuthorizationPolicy.permissions(action);
            ready(grant("LEARNING_TASK","GLOBAL",permissions.toArray(String[]::new)));
            assertDoesNotThrow(() -> policy.requireAdmin(9,action));
            ready(grant("ARTICLE","GLOBAL",permissions.toArray(String[]::new)));
            assertThrows(BusinessException.class,() -> policy.requireAdmin(9,action));
            ready(grant("LEARNING_TASK","ORGANIZATION",permissions.toArray(String[]::new)));
            assertThrows(BusinessException.class,() -> policy.requireAdmin(9,action));
        }
    }
    @Test void publishAndReviewRequireBothPermissions() {
        ready(grant("LEARNING_TASK","GLOBAL","learning-task:publish"));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.PUBLISH));
        ready(grant("LEARNING_TASK","GLOBAL","learning-submission:review"));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.REVIEW));
    }
    @Test void rootAndOrdinaryRevokeCannotGrantExceptionalRevoke() {
        ready(grant("AUTHORIZATION","GLOBAL","authorization:manage"));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.CREATE));
        ready(grant("LEARNING_TASK","GLOBAL","learning-task:revoke"));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.REVOKE_PARTICIPATED));
    }
    @Test void revocationAccountStateAndSourceFailureAreRechecked() {
        ready(grant("LEARNING_TASK","GLOBAL","learning-task:edit"));
        assertDoesNotThrow(() -> policy.requireAdmin(9,Action.EDIT));
        when(reader.current(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of()));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.EDIT));
        when(reader.current(9)).thenReturn(decision(Source.ASSIGNMENT,false,List.of(grant("LEARNING_TASK","GLOBAL","learning-task:edit"))));
        assertThrows(BusinessException.class,() -> policy.requireAdmin(9,Action.EDIT));
        when(reader.current(9)).thenThrow(new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/authorization-source-unavailable","unavailable"));
        var problem = assertThrows(ContractProblemException.class,() -> policy.requireAdmin(9,Action.EDIT));
        assertEquals("/problems/learning-task-dependency-unavailable",problem.getType());
    }
    @Test void mappingMatchesEveryCanonicalManagementOperation() throws Exception {
        Map<String,Action> actions = Map.ofEntries(
                Map.entry("listAdminLearningTasks",Action.READ),Map.entry("getAdminLearningTask",Action.READ),
                Map.entry("createLearningTask",Action.CREATE),Map.entry("updateLearningTask",Action.EDIT),
                Map.entry("previewLearningTaskAudience",Action.PREVIEW),Map.entry("getLearningTaskAudiencePreview",Action.PREVIEW),
                Map.entry("listLearningTaskAudiencePreviewMembers",Action.PREVIEW),Map.entry("publishLearningTask",Action.PUBLISH),
                Map.entry("addLearningTaskAssignments",Action.ADD),Map.entry("adjustLearningTaskDeadline",Action.DEADLINE),
                Map.entry("cancelLearningTask",Action.CANCEL),Map.entry("closeLearningTask",Action.CLOSE),
                Map.entry("revokeLearningTaskAssignment",Action.REVOKE),Map.entry("listLearningTaskAssignments",Action.ASSIGNMENT_READ),
                Map.entry("getAdminLearningTaskAssignment",Action.ASSIGNMENT_READ),Map.entry("getLearningTaskStatistics",Action.STATISTICS),
                Map.entry("listLearningTaskReviewQueue",Action.REVIEW_READ),Map.entry("getAdminLearningTaskSubmission",Action.REVIEW_READ),
                Map.entry("getAdminLearningTaskFileAccess",Action.REVIEW_READ),Map.entry("reviewLearningTaskSubmission",Action.REVIEW));
        var paths = new ObjectMapper().readTree(Files.readString(Path.of("../contracts/api/paths/learning-task.yaml")));
        int count = 0;
        for (var path : paths) {
            for (var operation : path) {
                var action = actions.get(operation.path("operationId").asText());
                if (action == null) { continue; }
                var matcher = java.util.regex.Pattern.compile("(?:learning-task|learning-submission):[a-z-]+").matcher(operation.path("description").asText());
                Set<String> codes = new java.util.HashSet<>();
                while (matcher.find()) { codes.add(matcher.group()); }
                if (action == Action.REVOKE) { codes.remove("learning-task:revoke-participated"); }
                assertEquals(codes,LearningTaskAuthorizationPolicy.permissions(action),operation.path("operationId").asText());
                count++;
            }
        }
        assertEquals(20,count);
    }
}
