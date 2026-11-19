package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityStatisticsQueriesTest {
    @Test void dependencyFailureIsUnavailableRatherThanZeroStatistics() {
        var mapper=mock(ActivityMapper.class);
        when(mapper.statistics(1)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test connection unavailable"));
        var queries=new ActivityStatisticsQueries(mock(CurrentAuthorization.class),mapper);
        var problem=assertThrows(ContractProblemException.class,()->queries.read(1,9));
        assertEquals(503,problem.getStatus().value());assertEquals("/problems/service-unavailable",problem.getType());
    }
    @Test void invalidSqlIsNotMisreportedAsAnUnavailableDependency() {
        var mapper=mock(ActivityMapper.class);
        var broken=new org.springframework.jdbc.BadSqlGrammarException("test","invalid",new java.sql.SQLException());
        when(mapper.statistics(1)).thenThrow(broken);
        var queries=new ActivityStatisticsQueries(mock(CurrentAuthorization.class),mapper);
        assertSame(broken,assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,()->queries.read(1,9)));
    }
}
