package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.bo.PersonBO.*;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.entity.PointApplication;
import cn.jualn.miniapp.module.points.mapper.*;
import cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Person storage/Reader/transaction handle and MySQL R3 SQL; only effective source input is a double.
 * Two scope rows retain distinct IDs even under one authority. No SQL or policy result double. */
@EnabledIfSystemProperty(named="unified.r3.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/unified_r3_migration_test")
class PointsR3SingleSourceDatabaseTest {
    @Test void detailPageCountAndCompleteStatisticsRejectUnionButKeepOneOrganizationAggregate() throws Exception {
        String configured=System.getProperty("unified.r3.jdbcUrl"),server=configured.substring(0,configured.lastIndexOf('/')+1);
        System.setProperty("person.test.jdbcUrl",server+"person_foundation_test_r3"+UUID.randomUUID().toString().replace("-",""));
        var f=new PersonFoundationDatabaseHttpTest();f.start();
        try {
            var factory=f.context.getBean(SqlSessionFactory.class);var config=factory.getConfiguration();
            for(String name:List.of("PointsMapper","PointsR3Mapper")) {
                var resource=new ClassPathResource("mapper/"+name+".xml");try(var in=resource.getInputStream()) {new XMLMapperBuilder(in,config,"mapper/"+name+".xml",config.getSqlFragments()).parse();}
            }
            var session=new SqlSessionTemplate(factory);var queries=session.getMapper(PointsR3Mapper.class);var db=session.getMapper(PointsMapper.class);
            long actor=f.user(),multi=f.user(),single=f.user();String a=f.org(),b=f.org();
            for(long user:List.of(multi,single)) {
                f.grant(9,user,Domain.ORGANIZATION,Action.READ,Action.MAINTAIN);
                f.service.replaceMemberships(9,f.subject(user),f.members(user==multi?new String[]{a,b}:new String[]{a}),f.key(),f.service.memberships(9,f.subject(user)).etag());
                var row=new PointApplication();row.setApplicantUserId(user);row.setTitle("R3 participant");row.setDescription("R3 participant");row.setFileIds("[\"explicit-query-fixture-only\"]");row.setStatus("PENDING");row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));db.insertApplication(row);
            }
            var duties=mock(UnifiedDutyReader.class);var sources=new AtomicReference<List<PersonBusinessScopes.Scope>>();var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            when(duties.effective(eq(actor),anyString(),any())).thenAnswer(i->sources.get());
            when(duties.decode(anyString(),eq(UnifiedModels.Selection.class))).thenAnswer(i->json.readValue((String)i.getArgument(0),UnifiedModels.Selection.class));
            var people=f.context.getBean(PersonBusinessScopes.class);
            var policy=new PointsAcceptancePolicy(db,f.context.getBean(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class),f.readers,people,duties,queries);
            var now=LocalDateTime.now(ZoneOffset.UTC).withNano(0);var until=now.plusDays(1);
            java.util.function.BiFunction<String,UnifiedModels.Target,PersonBusinessScopes.Scope> source=(id,target)->{
                try {return new PersonBusinessScopes.Scope(id,actor,"POINTS_PERSON_LEDGER","assignment-"+id,"SAME_AUTHORITY",null,json.writeValueAsString(new UnifiedModels.Selection("POINTS_PERSON_LEDGER",target,RbacTime.wire(until),null)),"[]",1,now,until,"change-"+id,null,null,null);} catch(Exception e){throw new IllegalStateException(e);}
            };
            var sa=source.apply("approval-A",new UnifiedModels.Target("ORGANIZATION",null,a));var sb=source.apply("approval-B",new UnifiedModels.Target("ORGANIZATION",null,b));
            sources.set(List.of(sa,sb));
            f.tx.execute(status->{
                var context=f.readers.open(actor,multi,single);var plan=policy.people(actor,null,null,context);
                assertEquals(List.of("approval-A","approval-B"),plan.sources().stream().map(PointsAcceptancePolicy.SourceTarget::scopeGrantId).toList());
                assertEquals(1,queries.participantCount(plan));assertEquals(f.subject(single),queries.personStatistics(plan,new PointsBO.Query(1,1,null,null)).get(0).personId());
                assertTrue(queries.personStatistics(plan,new PointsBO.Query(2,1,null,null)).isEmpty());
                assertEquals(404,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.ledger(actor,f.subject(multi),context.evaluatedAt())).getStatus().value());
                policy.ledger(actor,f.subject(single),context.evaluatedAt());return null;
            });
            // A single CURRENT aggregate approval includes A+B members without complete-person ledger authority.
            var ids=f.db.queryForList("SELECT id FROM person_membership WHERE organization_id=? AND ended_event_id IS NULL ORDER BY id",String.class,a);
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join(",",ids).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            f.db.update("INSERT INTO person_roster_completeness VALUES(?,'VERIFIED',?,?,'EXPLICIT_SYNTHETIC_COMPLETE_ROSTER')",a,UUID.randomUUID().toString(),digest);
            var stats=new PersonBusinessScopes.Scope("approval-stats-A",actor,"POINTS_STATS_CURRENT","assignment-stats","SAME_AUTHORITY",null,json.writeValueAsString(new UnifiedModels.Selection("POINTS_STATS_CURRENT",new UnifiedModels.Target("ORGANIZATION",null,a),RbacTime.wire(until),null)),"[]",1,now,until,"stats-change",null,null,null);
            when(duties.effective(eq(actor),eq("POINTS_STATS_CURRENT"),any())).thenReturn(List.of(stats));
            sources.set(List.of(sa));
            f.tx.execute(status->{var context=f.readers.open(actor,multi,single);var plan=policy.statistics(actor,a,false,context);
                var aggregate=queries.currentStatistics(plan,new PointsBO.Query(1,20,null,null));assertEquals(1,aggregate.size());assertEquals(2,aggregate.get(0).participantCount());
                assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.ledger(actor,f.subject(multi),context.evaluatedAt()));return null;});
            var person=source.apply("approval-PERSON",new UnifiedModels.Target("PERSON",f.subject(multi),null));sources.set(List.of(sa,sb,person));
            f.tx.execute(status->{var context=f.readers.open(actor,multi);assertEquals(2,queries.participantCount(policy.people(actor,null,null,context)));policy.ledger(actor,f.subject(multi),context.evaluatedAt());return null;});
            // Withdraw A: the independent PERSON witness continues. Withdraw PERSON: B alone still cannot read A+B.
            sources.set(List.of(sb,person));f.tx.execute(status->{var context=f.readers.open(actor,multi);policy.ledger(actor,f.subject(multi),context.evaluatedAt());return null;});
            sources.set(List.of(sb));f.tx.execute(status->{var context=f.readers.open(actor,multi);assertEquals(0,queries.participantCount(policy.people(actor,null,null,context)));assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->policy.ledger(actor,f.subject(multi),context.evaluatedAt()));return null;});
        } finally {f.context.close();System.clearProperty("person.test.jdbcUrl");}
    }
}
