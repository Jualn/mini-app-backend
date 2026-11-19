package cn.jualn.miniapp.module.points;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.points.controller.PointsProblemAdvice;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.points.mapper.PointsR3Mapper;
import cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import static cn.jualn.miniapp.module.points.PointsProblems.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointsDependencyDiagnosticsTest {
    @Test void databaseFailureReturnsUnavailableWithoutExposingDatabaseMessage() {
        var advice=new PointsProblemAdvice(new GlobalExceptionHandler());
        var response=advice.sourceUnavailable(new org.springframework.dao.DataAccessResourceFailureException("private SQL and connection details"));
        assertEquals(503,response.getStatusCode().value());
        assertEquals("BUSINESS_ELIGIBILITY",response.getBody().getProperties().get("dependency"));
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertFalse(response.getBody().getDetail().contains("private SQL"));
    }
    @Test void dependency503LogsOnceWithTraceAndCodeOriginsWithoutPrivateCauseMessage() {
        var logger=(Logger)LoggerFactory.getLogger(PointsProblemAdvice.class);
        var events=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {event.prepareForDeferredProcessing();super.append(event);}
        };
        events.start();logger.addAppender(events);
        String previous=MDC.get("traceId");MDC.put("traceId","points-diagnostic-test");
        try {
            var source=personSource();source.initCause(new IllegalStateException("PRIVATE_PERSON_FACT_MUST_NOT_BE_LOGGED"));
            var failure=unavailable(Dependency.PERSON_RESOLVER);failure.initCause(source);
            var response=new PointsProblemAdvice(new GlobalExceptionHandler()).problem(failure);
            assertEquals(503,response.getStatusCode().value());
            assertEquals("no-store",response.getHeaders().getCacheControl());
            assertEquals("/problems/points-dependency-unavailable",response.getBody().getType().toString());
            assertEquals("PERSON_RESOLVER",response.getBody().getProperties().get("dependency"));
            assertEquals("Required Points capability is unavailable",response.getBody().getDetail());
            assertEquals(1,events.list.size());var event=events.list.get(0);
            assertEquals(Level.WARN,event.getLevel());assertNull(event.getThrowableProxy());
            assertEquals("points-diagnostic-test",event.getMDCPropertyMap().get("traceId"));
            assertTrue(event.getFormattedMessage().contains("dependency=PERSON_RESOLVER"));
            assertTrue(event.getFormattedMessage().contains("causeOrigin=cn.jualn.miniapp.module.points."));
            assertTrue(event.getFormattedMessage().contains("PointsDependencyDiagnosticsTest.personSource("));
            assertTrue(event.getFormattedMessage().contains("causeProblemType=/problems/person-foundation-unavailable"));
            assertFalse(event.getFormattedMessage().contains("PersonProblems.problem("));
            assertFalse(event.getFormattedMessage().contains("PRIVATE_PERSON_FACT"));
        } finally {
            if(previous==null) MDC.remove("traceId");else MDC.put("traceId",previous);
            logger.detachAppender(events);events.stop();
        }
    }
    private ContractProblemException personSource() {
        return cn.jualn.miniapp.module.person.PersonProblems.unavailable();
    }
    @Test void malformedCauseProblemTypeIsNotLoggedAsRawText() {
        var logger=(Logger)LoggerFactory.getLogger(PointsProblemAdvice.class);var events=new ListAppender<ILoggingEvent>();
        events.start();logger.addAppender(events);
        try {
            var failure=unavailable(Dependency.PERSON_RESOLVER);
            failure.initCause(new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/private\nPRIVATE_FACT","PRIVATE_MESSAGE"));
            new PointsProblemAdvice(new GlobalExceptionHandler()).problem(failure);
            assertEquals(1,events.list.size());String message=events.list.get(0).getFormattedMessage();
            assertTrue(message.contains("causeProblemType=unknown"));
            assertFalse(message.contains("PRIVATE_FACT"));assertFalse(message.contains("PRIVATE_MESSAGE"));
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void ordinaryDenialsDoNotAcquireDependencyWarningLogs() {
        var logger=(Logger)LoggerFactory.getLogger(PointsProblemAdvice.class);var events=new ListAppender<ILoggingEvent>();
        events.start();logger.addAppender(events);
        try {
            var advice=new PointsProblemAdvice(new GlobalExceptionHandler());
            assertEquals(403,advice.problem(forbidden("self-review-forbidden")).getStatusCode().value());
            assertEquals(404,advice.problem(missing()).getStatusCode().value());
            assertEquals(409,advice.problem(conflict("application-finalized")).getStatusCode().value());
            assertTrue(events.list.isEmpty());
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void organizationStatisticsPreservesRosterAndMembershipFailureCauses() {
        var persons=mock(PersonBusinessScopes.class);var context=mock(PersonReadContext.class);
        var at=LocalDateTime.of(2026,10,26,22,50);when(context.evaluatedAt()).thenReturn(at);
        var policy=spy(new PointsAcceptancePolicy(mock(PointsMapper.class),mock(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class),mock(PersonFactReaders.class),persons,
                mock(UnifiedDutyReader.class),mock(PointsR3Mapper.class)));
        doReturn(List.of(new PointsAcceptancePolicy.SourceTarget("scope","assignment",new UnifiedModels.Target("PLATFORM",null,null))))
                .when(policy).sources(9,"POINTS_STATS_CURRENT",at);
        when(persons.organizations()).thenReturn(List.of("test-organization"));
        var source=new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/person-source-unavailable","Unverified test source");
        when(persons.rosterMembers("test-organization",at)).thenThrow(source);
        var roster=assertThrows(ContractProblemException.class,()->policy.statistics(9,null,false,context));
        assertEquals("PERSON_RESOLVER",roster.getMessage());assertSame(source,roster.getCause());
        doReturn(List.of("test-person")).when(persons).rosterMembers("test-organization",at);
        when(persons.completeMemberships("test-person",at)).thenThrow(source);
        var membership=assertThrows(ContractProblemException.class,()->policy.statistics(9,null,false,context));
        assertSame(source,membership.getCause());
    }
}
