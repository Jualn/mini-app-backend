package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.person.bo.PersonBO.*;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.entity.PointApplication;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owner commands, Reader and SQL. The reused foundation fixture's session/capability
 * and policy are explicit doubles; the separate full-application test verifies real auth. */
@EnabledIfSystemProperty(named="person.points.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/person_foundation_test_pointsb[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOwnershipQueryDatabaseTest {
    final PersonFoundationDatabaseHttpTest f=new PersonFoundationDatabaseHttpTest();
    PointsMapper points;
    @BeforeAll void start() throws Exception {
        System.setProperty("person.test.jdbcUrl",System.getProperty("person.points.test.jdbcUrl"));f.start();
        var factory=f.context.getBean(SqlSessionFactory.class);var configuration=factory.getConfiguration();
        var resource=new ClassPathResource("mapper/PointsMapper.xml");
        try(var input=resource.getInputStream()) {
            new XMLMapperBuilder(input,configuration,"mapper/PointsMapper.xml",configuration.getSqlFragments()).parse();
        }
        points=new SqlSessionTemplate(factory).getMapper(PointsMapper.class);
    }
    @AfterAll void stop() { if(f.context!=null) f.context.close(); }
    void participant(long user) {
        var row=new PointApplication();row.setApplicantUserId(user);row.setTitle("query fixture");row.setDescription("query fixture");
        row.setFileIds("[\"explicit-query-fixture-only\"]");row.setStatus("PENDING");row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));points.insertApplication(row);
    }
    void known(long user,String... organizations) throws Exception {
        f.grant(9,user,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
        f.service.replaceMemberships(9,f.subject(user),f.members(organizations),f.key(),f.service.memberships(9,f.subject(user)).etag());
    }
    @Test void samePredicateFiltersBeforePaginationAndOnlyBusinessParticipantsAreCandidates() throws Exception {
        long manager=f.user(),first=f.user(),second=f.user(),unknownNonparticipant=f.user(),outsider=f.user();
        known(first);known(second);participant(first);participant(second);participant(outsider);
        f.grant(manager,first,Domain.POINTS,Action.READ);f.grant(manager,second,Domain.POINTS,Action.READ);
        f.grant(manager,unknownNonparticipant,Domain.POINTS,Action.READ);
        f.tx.execute(status->{
            var context=f.readers.open(manager,first,second,outsider);
            var plan=f.readers.pointsQuery(manager,null,null,context);
            assertEquals(2,points.participantCount(plan));
            assertEquals(f.subject(first),points.personStatistics(plan,new PointsBO.Query(1,1,null,null)).get(0).personId());
            assertEquals(f.subject(second),points.personStatistics(plan,new PointsBO.Query(2,1,null,null)).get(0).personId());
            assertTrue(points.personStatistics(plan,new PointsBO.Query(3,1,null,null)).isEmpty());
            assertEquals(2,points.adminApplicationCount(plan,new PointsBO.Query(1,1,null,null)));
            assertEquals(1,points.adminApplications(plan,new PointsBO.Query(1,1,null,null)).size());
            assertEquals(PersonFactReaders.Decision.ALLOW,f.readers.authorizePerson(manager,first,Domain.POINTS,Action.READ,context).decision());
            assertEquals(PersonFactReaders.Decision.DENY,f.readers.authorizePerson(manager,outsider,Domain.POINTS,Action.READ,context).decision());
            var generic=f.readers.plan(manager,Domain.POINTS,Action.READ,null,context);
            assertEquals("READY",generic.decision());assertEquals(2,f.readers.count(generic.planHandle(),context));
            return null;
        });
        participant(unknownNonparticipant);
        f.tx.execute(status->{
            var context=f.readers.open(manager);
            assertEquals("MEMBERSHIP_UNKNOWN",f.readers.plan(manager,Domain.POINTS,Action.READ,null,context).reason());
            assertEquals(503,assertThrows(ContractProblemException.class,()->f.readers.pointsQuery(manager,null,null,context)).getStatus().value());status.setRollbackOnly();return null;
        });
    }
    @Test void completeOrganizationCoverageControlsGroupsAndCurrentMembershipFilters() throws Exception {
        // Organization scope needs completeness over the business cohort, including other test cases.
        for(long user:f.db.queryForList("SELECT DISTINCT p.applicant_user_id FROM point_application p LEFT JOIN person_fact_current h ON h.user_id=p.applicant_user_id WHERE h.membership_verification IS NULL OR h.membership_verification<>'VERIFIED'",Long.class)) known(user);
        long manager=f.user(),multi=f.user(),single=f.user();String a=f.org(),b=f.org();
        known(multi,a,b);known(single,a);participant(multi);participant(single);
        f.orgGrant(manager,a,Domain.POINTS,Action.READ);
        f.tx.execute(status->{
            var context=f.readers.open(manager,multi,single);var plan=f.readers.pointsQuery(manager,null,null,context);
            assertEquals(1,points.participantCount(plan));assertEquals(f.subject(single),points.personStatistics(plan,new PointsBO.Query(1,20,null,null)).get(0).personId());
            assertEquals(PersonFactReaders.Decision.DENY,f.readers.authorizePerson(manager,multi,Domain.POINTS,Action.READ,context).decision());
            var groups=points.organizationStatistics(plan,new PointsBO.Query(1,20,null,null));assertEquals(1,groups.size());assertEquals(a,groups.get(0).organizationId());
            assertEquals(404,assertThrows(ContractProblemException.class,()->f.readers.pointsQuery(manager,null,b,context)).getStatus().value());status.setRollbackOnly();return null;
        });
        f.orgGrant(manager,b,Domain.POINTS,Action.READ);
        f.tx.execute(status->{
            var context=f.readers.open(manager);var plan=f.readers.pointsQuery(manager,null,null,context);
            assertEquals(1,points.participantCount(plan));assertEquals(1,points.organizationCount(plan));
            var groups=points.organizationStatistics(plan,new PointsBO.Query(1,20,null,null));
            assertEquals(1,groups.stream().filter(g->g.organizationId().equals(a)).findFirst().orElseThrow().participantCount());
            assertTrue(groups.stream().noneMatch(g->g.organizationId().equals(b)));
            var filtered=f.readers.pointsQuery(manager,null,b,context);assertEquals(0,points.participantCount(filtered));assertEquals(0,points.organizationCount(filtered));return null;
        });
        f.service.replaceMemberships(9,f.subject(multi),f.members(a),f.key(),f.service.memberships(9,f.subject(multi)).etag());
        f.tx.execute(status->{var context=f.readers.open(manager);var plan=f.readers.pointsQuery(manager,null,b,context);assertEquals(0,points.participantCount(plan));assertEquals(0,points.organizationCount(plan));return null;});
    }
    @Test void finalEvaluationUsesOneFreshServerTimeForIdentityAndActionExpiry() throws Exception {
        long manager=f.user(),user=f.user();known(user);
        f.grant(9,user,Domain.IDENTITY,Action.READ,Action.MAINTAIN);f.catalog.confirmed=true;
        var target=new Target("PERSON",f.subject(user),null);
        f.bootstrap(Domain.POINTS,List.of(Action.READ,Action.REVIEW),target);
        var until=LocalDateTime.now(ZoneOffset.UTC).plusSeconds(4).withNano(0);
        f.service.grant(2,new ScopeChange(Long.toString(manager),target,new ActionSet(Domain.POINTS,List.of(Action.READ,Action.REVIEW)),until,"bounded test","case-ref"),f.key());
        f.service.changeIdentity(9,f.subject(user),new IdentityChange(ChangeKind.RECORD,"PARTY_ACTIVIST",until,null,"bounded identity","case-ref"),f.key(),f.service.identity(9,f.subject(user)).etag());
        f.tx.execute(status->{
            var context=f.readers.open(manager,user);
            assertEquals(PersonFactReaders.Decision.ALLOW,f.readers.authorizePerson(manager,user,Domain.POINTS,Action.READ,context).decision());
            assertEquals("ACTIVE",f.readers.identity(user,context).status());
            try { Thread.sleep(4100); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new RuntimeException(e); }
            var finalAt=f.readers.beginFinalCheck(context);
            assertTrue(finalAt.isAfter(context.evaluatedAt()));assertEquals(finalAt,context.finalEvaluatedAt());
            assertEquals(PersonFactReaders.Decision.DENY,f.readers.authorizeFinal(manager,user,Domain.POINTS,Action.READ,context).decision());
            assertEquals(PersonFactReaders.Decision.DENY,f.readers.authorizeFinal(manager,user,Domain.POINTS,Action.REVIEW,context).decision());
            assertEquals("EXPIRED",f.readers.identityFinal(user,context).status());return null;
        });
    }
}
