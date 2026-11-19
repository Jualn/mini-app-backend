package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.mapper.PointMaterialMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL, Mapper, transactions and decoder. COS/STS and content verdict are explicit provider doubles. */
@EnabledIfSystemProperty(named="points.media.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_media_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointMaterialDatabaseTest {
    AnnotationConfigApplicationContext context;JdbcTemplate db;PointMaterialService service;ControlledProvider provider;MutableClock clock;TransactionTemplate tx;
    static DataSource source(String base) { return new DriverManagerDataSource(base+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","root",""); }
    @BeforeAll void start() {
        String url=System.getProperty("points.media.test.jdbcUrl"),name=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        admin.execute("CREATE DATABASE "+name);admin.execute("CREATE DATABASE "+name+"_upgrade");
        var flyway=Flyway.configure().dataSource(source(url)).load();flyway.migrate();flyway.validate();
        var upgradeSource=source(url+"_upgrade");Flyway.configure().dataSource(upgradeSource).target("48").load().migrate();
        var prior=new JdbcTemplate(upgradeSource);prior.update("INSERT INTO user_profile(id,openid) VALUES(777,'private-media-upgrade-sentinel')");
        prior.update("INSERT INTO point_application(applicant_user_id,title,description,file_ids,status,created_at) VALUES(777,'preserved-V47','preserved','[\"original-proof\"]','PENDING','2026-10-17 12:30:00.123456')");
        String before=prior.queryForObject("SELECT SHA2(CONCAT(id,applicant_user_id,title,description,CAST(file_ids AS CHAR),status,created_at),256) FROM point_application WHERE applicant_user_id=777",String.class);
        var upgrade=Flyway.configure().dataSource(upgradeSource).load();upgrade.migrate();upgrade.validate();
        assertEquals(before,prior.queryForObject("SELECT SHA2(CONCAT(id,applicant_user_id,title,description,CAST(file_ids AS CHAR),status,created_at),256) FROM point_application WHERE applicant_user_id=777",String.class));
        assertEquals(0,prior.queryForObject("SELECT COUNT(*) FROM media_point_file",Integer.class));
        context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);service=context.getBean(PointMaterialService.class);
        clock=context.getBean(MutableClock.class);provider=context.getBean(ControlledProvider.class);tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        db.update("INSERT INTO user_profile(id,openid) VALUES(1001,'private-media-owner'),(1002,'private-media-other')");
    }
    @AfterAll void stop() throws Exception {
        if(context!=null) {
            Files.createDirectories(Path.of("target/points-integration"));
            Files.writeString(Path.of("target/points-integration/private-media.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "database",System.getProperty("points.media.test.jdbcUrl"),"real","MySQL / Mapper / transactions / ImageIO / private-file owner",
                "double","COS / STS / content safety verdict", "boundFiles",db.queryForObject("SELECT COUNT(*) FROM media_point_file WHERE state='BOUND'",Long.class),
                "publicAttachments",db.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE object_key LIKE 'points/%'",Long.class))));
            context.close();
        }
    }
    @BeforeEach void reset() { clock.value.set(Instant.parse("2026-10-18T12:00:00Z"));provider.reject.set(false);provider.failDelete.set(false); }
    String key() { return UUID.randomUUID().toString(); }
    List<Declaration> declaration() { return List.of(new Declaration("evidence.png","IMAGE","image/png",provider.png.length)); }
    File uploaded() { var upload=service.upload(1001,declaration(),key());provider.uploaded.put(upload.objectKeys().get(0),provider.png);return service.register(1001,upload.uploadId(),0,key()); }
    long application() {
        db.update("INSERT INTO point_application(applicant_user_id,title,description,file_ids,status,created_at) VALUES(1001,'fixture','isolated proof','[\"fixture\"]','PENDING',UTC_TIMESTAMP(6))");
        return db.queryForObject("SELECT MAX(id) FROM point_application WHERE applicant_user_id=1001",Long.class);
    }
    @Test void uploadReplayDoesNotPersistSecretsAndExpiryNeverRenewsOldKey() {
        var key=key();var upload=service.upload(1001,declaration(),key);assertEquals(upload,service.upload(1001,declaration(),key));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media_point_upload WHERE request_key=?",Integer.class,key));
        assertFalse(db.queryForObject("SELECT CAST(fingerprint AS CHAR) FROM media_point_upload WHERE request_key=?",String.class,key).contains("test-secret"));
        assertEquals("/problems/points-idempotency-conflict",assertThrows(ContractProblemException.class,()->service.upload(1001,List.of(new Declaration("other.png","IMAGE","image/png",provider.png.length)),key)).getType());
        clock.value.updateAndGet(i->i.plusSeconds(901));service.discardExpiredCredentials();
        assertEquals("/problems/points-upload-expired",assertThrows(ContractProblemException.class,()->service.upload(1001,declaration(),key)).getType());
    }
    @Test void registrationReplayAssociationAndPrivateCrossUserIsolation() {
        var upload=service.upload(1001,declaration(),key());provider.uploaded.put(upload.objectKeys().get(0),provider.png);String key=key();
        var first=service.register(1001,upload.uploadId(),0,key);assertEquals(first,service.register(1001,upload.uploadId(),0,key()));
        assertEquals(404,assertThrows(ContractProblemException.class,()->service.read(1002,first.fileId())).getStatus().value());
        assertEquals(404,assertThrows(ContractProblemException.class,()->service.register(1002,upload.uploadId(),0,key())).getStatus().value());
        long app=application();tx.executeWithoutResult(s->service.bind(1001,app,List.of(first.fileId())));
        assertEquals(app,service.read(1001,first.fileId()).applicationId());assertNull(service.read(1001,first.fileId()).unboundExpiresAt());
        assertEquals(first,service.register(1001,upload.uploadId(),0,key));
        assertEquals("/problems/points-file-unavailable",assertThrows(ContractProblemException.class,()->tx.executeWithoutResult(s->service.bind(1001,application(),List.of(first.fileId())))).getType());
    }
    @Test void bindingRollbackLeavesEveryFileUnboundAndSingleReuseFails() {
        var one=uploaded();var two=uploaded();long app=application();
        assertThrows(ContractProblemException.class,()->tx.executeWithoutResult(s->service.bind(1001,app,List.of(one.fileId(),two.fileId(),"not-owned"))));
        assertNull(service.read(1001,one.fileId()).applicationId());assertNull(service.read(1001,two.fileId()).applicationId());
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{service.bind(1001,app,List.of(one.fileId(),two.fileId()));throw new IllegalStateException("rollback fixture");}));
        assertNull(service.read(1001,one.fileId()).applicationId());
    }
    @Test void unknownSafetyFailsClosedAndBadBytesCannotRegister() {
        var upload=service.upload(1001,declaration(),key());provider.uploaded.put(upload.objectKeys().get(0),provider.png);provider.reject.set(true);
        assertEquals(503,assertThrows(ContractProblemException.class,()->service.register(1001,upload.uploadId(),0,key())).getStatus().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_point_file WHERE upload_id=? AND state='REGISTERED'",Integer.class,upload.uploadId()));
        provider.reject.set(false);var bad=service.upload(1001,List.of(new Declaration("bad.png","IMAGE","image/png",4)),key());provider.uploaded.put(bad.objectKeys().get(0),new byte[]{1,2,3,4});
        assertEquals(400,assertThrows(ContractProblemException.class,()->service.register(1001,bad.uploadId(),0,key())).getStatus().value());
    }
    @Test void cleanupFailureRetriesButBoundSnapshotAndAuditHistorySurvive() {
        var bound=uploaded();long app=application();tx.executeWithoutResult(s->service.bind(1001,app,List.of(bound.fileId())));
        var unbound=uploaded();clock.value.updateAndGet(i->i.plus(Duration.ofHours(25)));provider.failDelete.set(true);service.cleanupExpiredBatch();
        assertEquals("CLEANING",db.queryForObject("SELECT state FROM media_point_file WHERE file_id=?",String.class,unbound.fileId()));
        provider.failDelete.set(false);service.cleanupExpiredBatch();
        assertEquals("DELETED",db.queryForObject("SELECT state FROM media_point_file WHERE file_id=?",String.class,unbound.fileId()));
        assertEquals("BOUND",db.queryForObject("SELECT state FROM media_point_file WHERE file_id=?",String.class,bound.fileId()));
        assertTrue(provider.retained.containsKey("points/retained/"+bound.fileId()));assertEquals(1,db.queryForObject("SELECT source_deleted FROM media_point_file WHERE file_id=?",Integer.class,bound.fileId()));
        assertEquals(app,service.read(1001,bound.fileId()).applicationId());
    }
    @Test void registrationWindowCannotBeExtendedAndExpiredTemporaryReceiptCannotReplay() {
        var upload=service.upload(1001,declaration(),key());provider.uploaded.put(upload.objectKeys().get(0),provider.png);
        clock.value.updateAndGet(i->i.plus(Duration.ofHours(23)));String key=key();var file=service.register(1001,upload.uploadId(),0,key);
        clock.value.updateAndGet(i->i.plus(Duration.ofHours(2)));
        assertEquals(404,assertThrows(ContractProblemException.class,()->service.register(1001,upload.uploadId(),0,key())).getStatus().value());
        clock.value.updateAndGet(i->i.plus(Duration.ofHours(24)));
        assertEquals(404,assertThrows(ContractProblemException.class,()->service.register(1001,upload.uploadId(),0,key)).getStatus().value());
    }
    @Test void concurrentBindHasExactlyOneWinnerAndRollsBackLoser() throws Exception {
        var file=uploaded();long one=application(),two=application();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(long app:List.of(one,two)) results.add(executor.submit(()->{start.await();try { tx.executeWithoutResult(s->service.bind(1001,app,List.of(file.fileId())));return true; } catch(ContractProblemException refused) { assertEquals("/problems/points-file-unavailable",refused.getType());return false; } }));
            start.countDown();int winners=0;for(var result:results) if(result.get(10,TimeUnit.SECONDS)) winners++;
            assertEquals(1,winners);assertTrue(Set.of(one,two).contains(service.read(1001,file.fileId()).applicationId()));
        } finally { executor.shutdownNow(); }
    }
    @Configuration @MapperScan(basePackageClasses=PointMaterialMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
    static class Config {
        @Bean DataSource source() { return PointMaterialDatabaseTest.source(System.getProperty("points.media.test.jdbcUrl")); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/PointMaterialMapper.xml"));return factory.getObject();
        }
        @Bean(name="applicationClock") MutableClock clock() { return new MutableClock(); }
        @Bean ControlledProvider provider() { return new ControlledProvider(); }
        // This suite proves private lifecycle mechanisms, not the separately tested Points eligibility owner.
        @Bean PointMaterialAdmission admission() {return new PointMaterialAdmission() {public void begin(long user) {}public void finish(long user) {}};}
        @Bean PointMaterialService materials(PointMaterialMapper files,ControlledProvider provider,PlatformTransactionManager manager,MutableClock clock) { return new PointMaterialService(files,provider,manager,clock); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-10-18T12:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }public Clock withZone(ZoneId zone) { return this; }public Instant instant() { return value.get(); }
    }
    static class ControlledProvider implements PointMaterialProvider {
        final byte[] png;final Map<String,byte[]> uploaded=new ConcurrentHashMap<>(),retained=new ConcurrentHashMap<>();final AtomicBoolean reject=new AtomicBoolean(),failDelete=new AtomicBoolean();
        ControlledProvider() { try(var output=new ByteArrayOutputStream()) { ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",output);png=output.toByteArray(); } catch(Exception e) { throw new IllegalStateException(e); } }
        public Credentials credentials(String id,List<String> keys,LocalDateTime expires) { return new Credentials("isolated-private","region",keys,"test-id","test-secret","test-token",expires.toEpochSecond(ZoneOffset.UTC),null,id,expires); }
        public void retainAndInspect(String source,String target,long owner,Declaration d) {
            if(reject.get()) throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.CONTENT_SAFETY);
            byte[] bytes=uploaded.get(source);if(bytes==null || bytes.length!=d.sizeBytes()) throw PointMaterialService.invalidReference("uploadId");retained.putIfAbsent(target,bytes);
            Path path=null;try { path=Files.createTempFile("points-media-test-",".png");Files.write(path,retained.get(target));CosPointMaterialProvider.inspect(path,d); }
            catch(java.io.IOException e) { throw new IllegalStateException(e); }finally { if(path!=null) try { Files.deleteIfExists(path); } catch(Exception e) { throw new IllegalStateException(e); } }
        }
        public Access access(String target,LocalDateTime expires) { if(!retained.containsKey(target)) throw PointMaterialService.invalidReference("fileIds");return new Access("https://controlled.invalid/"+target+"?signature=controlled",expires); }
        public void delete(String key) { if(failDelete.get()) throw new IllegalStateException("controlled storage unavailable");uploaded.remove(key);retained.remove(key); }
    }
}
