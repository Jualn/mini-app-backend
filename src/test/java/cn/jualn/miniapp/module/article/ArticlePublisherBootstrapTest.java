package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.entity.ArticlePublisher;
import cn.jualn.miniapp.module.article.mapper.ArticlePublisherMapper;
import cn.jualn.miniapp.module.article.service.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArticlePublisherBootstrapTest {
    final ArticlePublisherMapper publishers=mock(ArticlePublisherMapper.class);
    final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
    final ArticleSafetyChecks safety=mock(ArticleSafetyChecks.class);
    final ArticlePublisherBootstrap bootstrap=new ArticlePublisherBootstrap(publishers,new CurrentAuthorization(reader),safety);

    void configured() {
        ReflectionTestUtils.setField(bootstrap,"name","Synthetic publisher");
        ReflectionTestUtils.setField(bootstrap,"adminId",9L);
    }
    Decision decision(Source source,boolean eligible,List<String> legacy,List<Grant> grants) {
        return new Decision(9,source,"version","catalog",LocalDateTime.now(),eligible,legacy,grants);
    }
    @ParameterizedTest @ValueSource(strings={"*","content:manage"})
    void explicitEligibleLegacyInitializationIsPreserved(String permission) {
        configured();when(reader.read(9)).thenReturn(decision(Source.LEGACY,true,List.of(permission),List.of()));
        when(publishers.insert(any(ArticlePublisher.class))).thenReturn(1);
        bootstrap.run(null);
        verify(safety).text(9,"Synthetic publisher");verify(publishers).insert(any(ArticlePublisher.class));
    }
    @Test void ordinaryAndIneligibleLegacyAccountsCannotInitialize() {
        configured();
        for(var d:List.of(decision(Source.LEGACY,true,List.of(),List.of()),decision(Source.LEGACY,false,List.of("*"),List.of()))) {
            when(reader.read(9)).thenReturn(d);
            assertThrows(BusinessException.class,()->bootstrap.run(null));
        }
        verifyNoInteractions(safety);verify(publishers,never()).insert(any(ArticlePublisher.class));
    }
    @Test void assignmentRootAndRevokedAccountCannotBorrowOldAdministratorRole() {
        configured();
        for(var grants:List.of(List.of(new Grant("a","ROOT","AUTHORIZATION","GLOBAL",List.of("authorization:root-manage"),null)),List.<Grant>of())) {
            when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of(),grants));
            var error=assertThrows(ContractProblemException.class,()->bootstrap.run(null));
            assertEquals(503,error.getStatus().value());assertEquals("/problems/authorization-capability-not-ready",error.getType());
        }
        verifyNoInteractions(safety);verify(publishers,never()).insert(any(ArticlePublisher.class));
    }
    @Test void unreadableAuthorityDoesNotWriteOrPerformExternalSafetyChecks() {
        configured();when(reader.read(9)).thenThrow(new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/authorization-source-unavailable","Synthetic read failure"));
        assertThrows(ContractProblemException.class,()->bootstrap.run(null));
        verifyNoInteractions(safety);verify(publishers,never()).insert(any(ArticlePublisher.class));
    }
    @Test void disabledOrAlreadyInitializedBootstrapRemainsInert() {
        bootstrap.run(null);configured();when(publishers.selectById(1L)).thenReturn(new ArticlePublisher());bootstrap.run(null);
        verifyNoInteractions(reader,safety);verify(publishers,never()).insert(any(ArticlePublisher.class));
    }
}
