package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.module.audit.async.*;
import cn.jualn.miniapp.module.audit.mapper.MediaVideoAuditMapper;
import cn.jualn.miniapp.third.cos.client.CosVideoAuditClient;
import cn.jualn.miniapp.third.cos.config.*;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL, Job rows, mapper and fenced transactions; CI calls remain explicit doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")
class CosVideoAuditDatabaseTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc; MediaVideoAuditMapper rows; AsyncJobMapper jobs;
    CosVideoAuditWriter writer; CosVideoSafetyCheckService safety; CosVideoAuditProperties policy;
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); rows=context.getBean(MediaVideoAuditMapper.class);
        jobs=context.getBean(AsyncJobMapper.class); writer=context.getBean(CosVideoAuditWriter.class);
        policy=new CosVideoAuditProperties(); policy.setEnabled(true); policy.setBizType("test_strategy");
        safety=new CosVideoSafetyCheckService(writer,new CosProperties("synthetic","synthetic","bucket-1250000000","ap-guangzhou","https://cdn.example",1800,"cdn.example"),policy);
        jdbc.update("DELETE FROM async_job WHERE job_type=?",CosVideoAuditWriter.JOB_TYPE); jdbc.update("DELETE FROM media_video_audit");
    }
    @AfterEach void close() { context.close(); }
    MediaAttachmentBO video() { return MediaAttachmentBO.builder().id(777L).kind("VIDEO").verified(true).objectKey("article/retained/9/video").sizeBytes(100L).durationSeconds(30d).build(); }
    long reserve() { safety.reserve(777,"article/retained/9/video",100,9); return rows.find(777,safety.policyKey()).getId(); }
    CosVideoAuditPayload payload(long id,int sequence) { return new CosVideoAuditPayload(id,sequence); }
    JobExecutionContext claim(long id,int sequence) throws Exception {
        AsyncJob job=jobs.selectByDedupe(CosVideoAuditWriter.JOB_TYPE,"video-audit:"+id+":"+sequence);
        assertNotNull(job); String token=UUID.randomUUID().toString();
        assertEquals(1,jobs.claim(job.getId(),token,LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(120)));
        var constructor=JobExecutionContext.class.getDeclaredConstructor(long.class,String.class,AsyncJobMapper.class,Duration.class); constructor.setAccessible(true);
        return constructor.newInstance(job.getId(),token,jobs,Duration.ofSeconds(90));
    }
    @Test void reservationAndJobCommitTogetherAndRollbackTogether() {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{reserve(); throw new IllegalStateException("synthetic rollback");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_video_audit",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type=?",Integer.class,CosVideoAuditWriter.JOB_TYPE));
        long id=reserve(); assertEquals(id,reserve());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type=?",Integer.class,CosVideoAuditWriter.JOB_TYPE));
    }
    @Test void concurrentReservationProducesOneResourcePolicyAndOneJob() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> results=new ArrayList<>(); for(int i=0;i<4;i++) results.add(pool.submit(this::reserve));
            Set<Long> ids=new HashSet<>(); for(var result:results) ids.add(result.get(10,TimeUnit.SECONDS)); assertEquals(1,ids.size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type=?",Integer.class,CosVideoAuditWriter.JOB_TYPE));
        } finally { pool.shutdownNow(); }
    }
    @Test void pollResultNeedsExactIdentityAndExplicitPassBeforePublication() throws Exception {
        long id=reserve(); var submit=payload(id,0); var owner=claim(id,0);
        var client=mock(CosVideoAuditClient.class); var handler=new CosVideoAuditJobHandler(writer,client,policy);
        when(client.submit(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/video","Submitted",CosVideoAuditResult.Decision.PENDING));
        handler.handle(submit,owner); assertEquals("RUNNING",rows.selectById(id).getState());
        assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->safety.requirePassed(video(),9));
        var poll=payload(id,1); var pollOwner=claim(id,1);
        var forged=new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/other","Success",CosVideoAuditResult.Decision.PASS);
        assertThrows(IllegalArgumentException.class,()->writer.observed(poll,forged,30,pollOwner)); assertEquals("RUNNING",rows.selectById(id).getState());
        var normal=new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/video","Success",CosVideoAuditResult.Decision.PASS);
        writer.observed(poll,normal,30,pollOwner); assertEquals("PASS",rows.selectById(id).getState());
        assertDoesNotThrow(()->safety.requirePassed(video(),9));
        policy.setPolicyRevision("2"); assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->safety.requirePassed(video(),9));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM media_video_audit",Integer.class));
        // A stale duplicate cannot overwrite an already completed result.
        writer.observed(poll,new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/video","Success",CosVideoAuditResult.Decision.REJECT),30,pollOwner);
        assertEquals("PASS",rows.selectById(id).getState());
    }
    @Test void crashAfterSubmissionClaimBecomesUnknownAndCannotResubmit() throws Exception {
        long id=reserve(); var payload=payload(id,0); var owner=claim(id,0);
        assertEquals("SUBMITTING",writer.begin(payload,owner).getState());
        var client=mock(CosVideoAuditClient.class); var handler=new CosVideoAuditJobHandler(writer,client,policy);
        assertThrows(UnknownOutcomeException.class,()->handler.handle(payload,owner));
        assertEquals("UNKNOWN",rows.selectById(id).getState()); verifyNoInteractions(client);
        assertThrows(UnknownOutcomeException.class,()->handler.handle(payload,owner)); verifyNoInteractions(client);
    }
    @Test void pendingPollSchedulesOneSuccessorAndStaleOwnerCannotWrite() throws Exception {
        long id=reserve(); var owner=claim(id,0); writer.begin(payload(id,0),owner); writer.submitted(payload(id,0),"v123",30,owner);
        var poll=payload(id,1); var pollOwner=claim(id,1);
        var pending=new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/video","Auditing",CosVideoAuditResult.Decision.PENDING);
        writer.observed(poll,pending,30,pollOwner); writer.observed(poll,pending,30,pollOwner);
        assertEquals(2,rows.selectById(id).getPollSequence());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type=?",Integer.class,CosVideoAuditWriter.JOB_TYPE));
        var successor=claim(id,2); jdbc.update("UPDATE async_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",successor.jobId());
        assertThrows(JobOwnershipLostException.class,()->writer.observed(payload(id,2),pending,30,successor));
        assertEquals(2,rows.selectById(id).getPollSequence());
    }
    @Test void reviewRejectionAndExpiredTasksNeverPass() throws Exception {
        for(var decision:List.of(CosVideoAuditResult.Decision.REVIEW,CosVideoAuditResult.Decision.REJECT,CosVideoAuditResult.Decision.FAILED)) {
            jdbc.update("DELETE FROM async_job WHERE job_type=?",CosVideoAuditWriter.JOB_TYPE); jdbc.update("DELETE FROM media_video_audit");
            long id=reserve(); var owner=claim(id,0); writer.begin(payload(id,0),owner); writer.submitted(payload(id,0),"v123",30,owner);
            writer.observed(payload(id,1),new CosVideoAuditResult("v123","article-video-audit:"+id,"article/retained/9/video","Success",decision),30,claim(id,1));
            assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->safety.requirePassed(video(),9));
        }
        jdbc.update("DELETE FROM async_job WHERE job_type=?",CosVideoAuditWriter.JOB_TYPE); jdbc.update("DELETE FROM media_video_audit");
        long id=reserve(); jdbc.update("UPDATE media_video_audit SET deadline_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",id);
        assertEquals("FAILED",writer.begin(payload(id,0),claim(id,0)).getState());
    }

    @Configuration @EnableTransactionManagement @Import({CosVideoAuditWriter.class,JobService.class})
    static class Config {
        @Bean("objectMapper") ObjectMapper json() { return new ObjectMapper(); }
        @Bean DataSource source() {
            String url=System.getProperty("event.test.jdbcUrl"); if(url==null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/article_contract_test")) throw new IllegalArgumentException("Dedicated disposable Article DB required");
            return new DriverManagerDataSource(url+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        }
        @Bean DataSourceTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/AsyncJobMapper.xml")); return factory.getObject();
        }
        @Bean MapperFactoryBean<MediaVideoAuditMapper> rows(SqlSessionFactory sessions) { var factory=new MapperFactoryBean<>(MediaVideoAuditMapper.class); factory.setSqlSessionFactory(sessions); return factory; }
        @Bean MapperFactoryBean<AsyncJobMapper> jobs(SqlSessionFactory sessions) { var factory=new MapperFactoryBean<>(AsyncJobMapper.class); factory.setSqlSessionFactory(sessions); return factory; }
    }
}
