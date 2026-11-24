package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.module.authorization.model.RbacModels.Decision;
import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class LearningTaskConfigurationScopeTest {
    @Test void savedOrganizationIdDoesNotGrantDirectoryDisclosure() {
        var persons = mock(PersonBusinessScopes.class);
        var auth = mock(TransactionalAuthorization.class);
        var duties = mock(UnifiedDutyReader.class);
        var tasks = mock(LearningTaskMapper.class);
        var row = new LearningTask(); row.setId(11L); row.setManagementTarget("trusted-target");
        when(tasks.selectById(11L)).thenReturn(row);
        when(duties.decode("trusted-target", Target.class)).thenReturn(new Target("ORGANIZATION", null, "owner"));
        var at = LocalDateTime.of(2026, 11, 22, 18, 0);
        when(auth.currentNow(9)).thenReturn(new Decision(9, Source.ASSIGNMENT, "v", "c", at, true, List.of(), List.of()));
        when(persons.organizationFact("owner")).thenReturn(new PersonBusinessScopes.OrganizationFact("owner", "负责组织", "ACTIVE"));
        var policy = new LearningTaskScopePolicy(persons, auth, duties, tasks, mock(UserLearningTaskRecipientService.class),
                mock(UserCurrentLoginAccess.class), mock(LearningTaskResponsibilities.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals("负责组织", policy.configurationOrganizationName(9, 11, "owner"));
            assertNull(policy.configurationOrganizationName(9, 11, "forged-group"));
            verify(persons, never()).organizationFact("forged-group");
            verify(persons).organizationFact("owner");
        } finally {
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        }
    }
}
