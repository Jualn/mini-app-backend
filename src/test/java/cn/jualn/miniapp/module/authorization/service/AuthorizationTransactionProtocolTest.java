package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring propagation/commit callbacks; JDBC locks themselves are verified in MySQL suites. */
class AuthorizationTransactionProtocolTest {
    final PersonMapper persons=mock(PersonMapper.class);
    final RbacMapper rbac=mock(RbacMapper.class);
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    TransactionalAuthorization authorization;
    TransactionTemplate transaction;
    Connection connection;

    @BeforeEach void setup() throws Exception {
        connection=mock(Connection.class);when(connection.getAutoCommit()).thenReturn(true);
        var source=mock(DataSource.class);when(source.getConnection()).thenReturn(connection);
        var manager=new DataSourceTransactionManager(source);
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        var proxy=new ProxyFactory(new TransactionalAuthorization(rbac,reader,new PersonAuthorityGate(persons)));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        authorization=(TransactionalAuthorization)proxy.getProxy();
        when(rbac.lockGovernance()).thenReturn("ROOT");
        when(rbac.lockSubject(anyLong())).thenAnswer(call->{
            var subject=new RbacModels.Subject();subject.setSource("ASSIGNMENT");subject.setVersion("version");return subject;
        });
    }

    @Test void entryRequiresCallerTransactionAndLocksGateBeforeOrderedSubjects() {
        assertThrows(IllegalTransactionStateException.class,()->authorization.begin(PersonAuthorityGate.Access.SHARED,9));
        verifyNoInteractions(persons,rbac);
        when(persons.gateExclusive()).thenReturn(7L);
        long epoch=transaction.execute(status->authorization.begin(PersonAuthorityGate.Access.EXCLUSIVE,9,2,9));
        assertEquals(7L,epoch);
        var order=inOrder(persons,rbac);
        order.verify(persons).gateExclusive();order.verify(rbac).lockGovernance();
        order.verify(rbac).lockSubject(2);order.verify(rbac).lockSubject(9);
        verify(rbac,times(2)).lockSubject(anyLong());
    }

    @Test void unavailableGovernanceNeverReadsOrLocksSubject() {
        when(rbac.lockGovernance()).thenReturn(null);
        assertThrows(RuntimeException.class,()->transaction.execute(status->authorization.begin(PersonAuthorityGate.Access.SHARED,9)));
        var order=inOrder(persons,rbac);order.verify(persons).gateShared();order.verify(rbac).lockGovernance();
        verify(rbac,never()).lockSubject(anyLong());verifyNoInteractions(reader);
    }

    @Test void finalAuthorizationFailureRollsBackTheOwningTransaction() throws Exception {
        var lease=new BusinessAuthorizationLease(authorization,reader);
        var checks=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->lease.require(9,decision->{
            if(checks.incrementAndGet()==2) throw new IllegalStateException("expired before commit");
        })));
        assertEquals(2,checks.get());verify(reader,times(2)).readCurrent(eq(9L),any());
        verify(connection).rollback();verify(connection,never()).commit();
    }
}
