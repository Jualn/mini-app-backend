package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskAuthorizationPolicy;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskRecipientOptions;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskScopePolicy;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonBusinessMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.access.LearningRecipientReader;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real Spring proxies/transaction completion; JDBC, authorization and directory rows are doubles. */
class LearningTaskRecipientTransactionTest {
    private final PersonMapper facts = mock(PersonMapper.class);
    private LearningTaskRecipientOptions options;
    private PersonBusinessScopes persons;
    private Connection connection;
    private DataSourceTransactionManager manager;

    @BeforeEach
    void setup() throws Exception {
        connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        manager = new DataSourceTransactionManager(source);
        var catalog = mock(PersonCatalog.class);
        when(catalog.value()).thenReturn(new PersonCatalog.Catalog("fixture", "CONFIRMED", List.of()));
        when(catalog.confirmed("stage-a")).thenReturn(true);
        when(facts.now()).thenReturn(LocalDateTime.of(2026, 11, 22, 15, 15));
        // No fact head: identity/stage evidence is unknown, not an explicit absence.
        persons = proxied(new PersonBusinessScopes(mock(PersonBusinessMapper.class), facts,
                mock(ManagedPersonMapper.class), catalog), manager);
        var authorization = mock(LearningTaskAuthorizationPolicy.class);
        var scopes = mock(LearningTaskScopePolicy.class);
        var responsibilities = mock(LearningTaskResponsibilities.class);
        var reader = mock(LearningRecipientReader.class);
        var target = new Target("PLATFORM", null, null);
        when(authorization.authorize(any(), any(), any())).thenReturn(9L);
        when(scopes.optionTarget(9L, 42L, null, null)).thenReturn(target);
        when(scopes.optionCeilings(9L, target, false)).thenReturn(List.of(target));
        when(scopes.optionCovered("PERSON", "p1", List.of(target))).thenReturn(true);
        when(scopes.articleOptionsVersion(9L, 42L, null, null)).thenReturn("authority-v1");
        when(reader.candidates(anyBoolean(), anyList(), anyList(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(new LearningRecipientReader.Item("p1", "p1", "候选人")));
        when(responsibilities.recipient(Subject.person("p1")))
                .thenReturn(new Recipient(Subject.person("p1"), null, null, "NO_BINDING"));
        options = proxied(new LearningTaskRecipientOptions(authorization, scopes, persons, catalog,
                responsibilities, reader), manager);
    }

    @Test
    void unknownOptionalStageReturnsCandidateAndCommits() throws Exception {
        var page = list(List.of());
        assertEquals(1, page.items().size());
        assertNull(page.items().get(0).display().developmentStage());
        assertFalse(page.items().get(0).selectable());
        assertEquals("NO_BINDING", page.items().get(0).unavailableReason());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void unknownRequiredStagePreservesOriginalFailureAndRollsBack() throws Exception {
        var failure = assertThrows(ContractProblemException.class, () -> list(List.of("stage-a")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void databaseFailureDuringOptionalStageReadStillRollsBack() throws Exception {
        when(facts.head("p1")).thenReturn(null)
                .thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThrows(DataAccessResourceFailureException.class, () -> list(List.of()));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void optionalDisplayStillRequiresCallerTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> persons.developmentStageForDisplay("p1", LocalDateTime.now()));
    }

    @Test
    void catchingStrictReaderFailureOutsideProxyReproducesUnexpectedRollback() throws Exception {
        var transaction = new TransactionTemplate(manager);
        assertThrows(UnexpectedRollbackException.class, () -> transaction.executeWithoutResult(status -> {
            assertThrows(ContractProblemException.class,
                    () -> persons.developmentStage("p1", LocalDateTime.now()));
        }));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private LearningTaskRecipientOptions.Page list(List<String> stages) {
        return options.list(42L, null, null, "PERSON", null, List.of(), stages, null, 20);
    }

    private <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }
}
