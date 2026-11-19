package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.points.service.CurrentPointsDependencies;
import cn.jualn.miniapp.module.points.service.PointsPrivateMaterials;
import cn.jualn.miniapp.module.points.service.PointsResultNotifications;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction binding, suspension and MANDATORY proxy; JDBC and owner reads are doubles.
 * This proves propagation/routing only, not database authorization or isolation. */
class PointsTransactionBoundaryTest {
    final CurrentAuthorization preflight=mock(CurrentAuthorization.class);
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final RbacMapper mapper=mock(RbacMapper.class);
    final RbacCatalog catalog=mock(RbacCatalog.class);
    final AdminTokenService tokens=mock(AdminTokenService.class);
    final PersonFactReaders people=mock(PersonFactReaders.class);
    final LocalDateTime now=LocalDateTime.of(2026,10,26,14,0);
    TransactionTemplate transaction;
    TransactionalAuthorization locked;
    CurrentPointsDependencies dependencies;

    @BeforeEach void setup() throws Exception {
        var source=mock(DataSource.class);
        when(source.getConnection()).thenAnswer(call->{
            var connection=mock(Connection.class);when(connection.getAutoCommit()).thenReturn(true);return connection;
        });
        var manager=new DataSourceTransactionManager(source);transaction=new TransactionTemplate(manager);
        var proxy=new ProxyFactory(new TransactionalAuthorization(mapper,reader,new cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate(mock(cn.jualn.miniapp.module.person.mapper.PersonMapper.class))));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        locked=(TransactionalAuthorization)proxy.getProxy();
        dependencies=new CurrentPointsDependencies(preflight,catalog,mock(UserAccountService.class),tokens,
                people,mock(PersonCatalog.class),locked,manager,mock(PointsResultNotifications.class),
                mock(PointsMapper.class),mock(PointsPrivateMaterials.class),mock(UserCurrentLoginAccess.class));
        when(tokens.requireAuthenticatedSession()).thenReturn(9L);
        when(tokens.requireAuthenticatedSessionIdentity()).thenReturn(9L);
        when(catalog.supports(eq("POINTS"),anySet())).thenReturn(true);
        when(mapper.now()).thenReturn(now);
        when(people.open(any(long[].class))).thenReturn(mock(PersonReadContext.class));
        when(preflight.current(9)).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Preflight must precede the Person gate snapshot");
            return decision(true);
        });
        when(reader.readCurrent(9,now)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return decision(true);
        });
    }
    RbacModels.Decision decision(boolean allowed) {
        return new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","catalog",now,true,List.of(),
                allowed?List.of(new RbacModels.Grant("grant","reviewer","POINTS","GLOBAL",List.of("points:read"),null)):List.of());
    }
    @Test void controllerPreflightOutsideTransactionDoesNotCallMandatoryReader() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(9,dependencies.actor(Action.READ));
        verify(preflight).current(9);verifyNoInteractions(mapper,reader);
    }
    @Test void serviceSuspendsPreflightThenUsesCallerTransactionAfterGateAndCleansUp() {
        transaction.execute(status->{
            assertEquals(9,dependencies.actor(Action.READ));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            verifyNoInteractions(reader);
            dependencies.begin(Action.READ,9);
            assertEquals(9,dependencies.actor(Action.READ));
            verify(reader,times(2)).readCurrent(9,now);
            return null;
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        // A completed transaction must not leave a Person context that routes this next request to MANDATORY.
        assertEquals(9,dependencies.actor(Action.READ));
        verify(reader,times(2)).readCurrent(9,now);
        verify(preflight,times(2)).current(9);
    }
    @Test void lockedRecheckStillDeniesRevocationAndMandatoryStillRejectsMissingTransaction() {
        assertThrows(IllegalTransactionStateException.class,()->locked.currentNow(9));
        doReturn(decision(true),decision(false)).when(reader).readCurrent(9,now);
        assertThrows(BusinessException.class,()->transaction.execute(status->{
            dependencies.begin(Action.READ,9);
            dependencies.actor(Action.READ);return null;
        }));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(9,dependencies.actor(Action.READ));
    }
    @Test void correctionAuthorityIsRecheckedByRealBeforeCommitSynchronization() {
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        var acceptance=mock(cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy.class);
        when(provider.getIfAvailable()).thenReturn(acceptance);when(provider.getObject()).thenReturn(acceptance);
        org.springframework.test.util.ReflectionTestUtils.setField(dependencies,"acceptance",provider);
        when(people.beginFinalCheck(any())).thenReturn(now);
        var manager=new RbacModels.Decision(9,RbacModels.Source.ASSIGNMENT,"v","catalog",now,true,List.of(),
                List.of(new RbacModels.Grant("grant","POINTS_MANAGER","POINTS","GLOBAL",List.of("points:read","points:adjust","points:adjust-approve"),null)));
        doReturn(manager,manager,decision(false)).when(reader).readCurrent(9,now);
        var app=new cn.jualn.miniapp.module.points.bo.PointsBO.Application(101,7,"title","description",List.of(),cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,now,8L,now,null,7,501L);
        assertThrows(BusinessException.class,()->transaction.execute(status->{
            dependencies.begin(Action.CORRECT,9);
            dependencies.finishApplication(Action.CORRECT,9,app,false);
            return null; // The service check passed; the actual commit callback must still deny.
        }));
        verify(reader,times(3)).readCurrent(9,now);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

}
