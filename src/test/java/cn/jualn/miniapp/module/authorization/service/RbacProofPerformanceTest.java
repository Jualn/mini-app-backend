package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;

import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/** Synthetic service benchmark over loopback HTTP, not the production Controller/identity pipeline. */
@EnabledIfSystemProperty(named="rbac.perf.phase",matches="before|after")
class RbacProofPerformanceTest {
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class Counts implements Interceptor {
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        public Object intercept(Invocation invocation)throws Throwable {
            queries.merge(((MappedStatement)invocation.getArgs()[0]).getId(),1,Integer::sum);
            return invocation.proceed();
        }
        Map<String,Integer> take(){var copy=new TreeMap<>(queries);queries.clear();return copy;}
    }
    @Test void compareIdenticalSyntheticDataset()throws Exception {
        String phase=System.getProperty("rbac.perf.phase");
        String url=System.getProperty("event.test.jdbcUrl");RbacOfflineTool.requireSyntheticUrl(url);
        RbacOfflineDatabaseTest.url=url;
        boolean seed="before".equals(phase) && !Boolean.getBoolean("rbac.perf.reuse");
        if(seed)RbacOfflineDatabaseTest.migrate();
        var f=new RbacOfflineDatabaseTest();
        if(seed) {
            f.setup();f.bootstrap();f.switchUser(3);f.switchUser(4);
            f.governance.grant(f.grant(2,3,"NOTICE_MONITOR","NOTIFICATION"));
            for(int i=0;i<100;i++) {
                f.governance.grant(f.grant(2,4,"NOTICE_MONITOR",i%2==0?"NOTIFICATION":"SYSTEM"));
                f.revoke(2,f.mapper.assignments(4).stream().filter(a->a.getRevokedAt()==null).findFirst().orElseThrow());
            }
            // Only receipt identities differ; no live Assignment is linked to these historical synthetic operations.
            f.jdbc.execute("INSERT INTO rbac_offline_operation SELECT UUID(),'synthetic-perf','BOOTSTRAP','synthetic-executor','synthetic-approval',REPEAT('a',64),REPEAT('b',64),JSON_OBJECT(),JSON_ARRAY(JSON_OBJECT(),JSON_OBJECT()),1,JSON_OBJECT('effects',JSON_ARRAY(JSON_OBJECT('assignmentId',UUID()),JSON_OBJECT('assignmentId',UUID()))),UTC_TIMESTAMP(6) FROM (WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<100) SELECT n FROM seq) s CROSS JOIN (WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<100) SELECT n FROM seq) t");
        } else {
            f.context=new AnnotationConfigApplicationContext(RbacOfflineDatabaseTest.Config.class);
            f.jdbc=new JdbcTemplate(f.context.getBean(javax.sql.DataSource.class));f.mapper=f.context.getBean(RbacMapper.class);
            f.reader=f.context.getBean(RbacAuthorizationReader.class);
        }
        var queries=f.context.getBean(RbacQueryService.class);
        var factory=f.context.getBean(SqlSessionFactory.class);var counts=new Counts();factory.getConfiguration().addInterceptor(counts);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var pool=Executors.newSingleThreadExecutor();server.setExecutor(pool);
        server.createContext("/",exchange->{
            try {
                switch(exchange.getRequestURI().getPath()) {
                    case "/ordinary" -> assertTrue(f.reader.read(3).allows("notice:read","NOTIFICATION"));
                    case "/offline" -> assertTrue(f.reader.read(2).allows("authorization:read","AUTHORIZATION"));
                    case "/page20" -> assertEquals(20,queries.assignments(2,4,new RbacQueryService.AssignmentQuery(null,null,null,20),null).items().size());
                    case "/page100" -> assertEquals(100,queries.assignments(2,4,new RbacQueryService.AssignmentQuery(null,null,null,100),null).items().size());
                    default -> throw new IllegalArgumentException();
                }
                exchange.sendResponseHeaders(200,0);
            } catch(Throwable error) {exchange.sendResponseHeaders(500,0);}
            finally {exchange.close();}
        });server.start();
        var result=new LinkedHashMap<String,Object>();
        try {
            result.put("phase",phase);result.put("scope","loopback HTTP test bridge -> real Reader/QueryService proxies/MySQL; no production Controller or authentication overhead");
            result.put("dataset",f.jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM rbac_offline_operation) operations,(SELECT COUNT(*) FROM rbac_offline_effect) effects,(SELECT COUNT(*) FROM rbac_authorization_change) changes,(SELECT COUNT(*) FROM rbac_user_role_assignment) assignments"));
            result.put("settings",f.jdbc.queryForList("SHOW VARIABLES WHERE Variable_name IN ('version','innodb_buffer_pool_size','innodb_buffer_pool_load_at_startup','innodb_buffer_pool_dump_at_shutdown')"));
            result.put("datasetFingerprint",f.jdbc.queryForList("SELECT id,grant_change_id,version FROM rbac_user_role_assignment ORDER BY id"));
            var samples=new LinkedHashMap<String,Object>();var client=HttpClient.newHttpClient();
            for(String route:List.of("ordinary","offline","page20","page100")) {
                restartOwnedDatabase(f.context.getBean(javax.sql.DataSource.class));
                counts.take();var rows=new ArrayList<Object>();
                for(int i=0;i<16;i++) {
                    long start=System.nanoTime();var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"+route)).timeout(Duration.ofSeconds(30)).build(),HttpResponse.BodyHandlers.discarding());
                    double millis=(System.nanoTime()-start)/1_000_000.0;assertEquals(200,response.statusCode(),route);
                    rows.add(Map.of("cache",i==0?"first request after MySQL restart; InnoDB load disabled; OS cache NOT flushed":i<=3?"warmup":"warm sample","milliseconds",millis,"mapperQueries",counts.take()));
                }
                samples.put(route,rows);
            }
            result.put("samples",samples);
            var planRows=new ArrayList<Object>();
            for(long user:List.of(3L,2L,4L)) {
                var assignments=f.mapper.assignments(user);if(user==4)assignments=assignments.subList(0,100);
                Assignment first=assignments.get(0);
                Map<String,Object> parameters=Map.of("assignmentId",first.getId(),"grantChangeId",first.getGrantChangeId(),"assignments",assignments);
                MappedStatement ms=factory.getConfiguration().getMappedStatement(RbacMapper.class.getName()+".offlineGrantProofs");
                var bound=ms.getBoundSql(parameters);var lines=new ArrayList<String>();
                try(var connection=f.context.getBean(javax.sql.DataSource.class).getConnection();var statement=connection.prepareStatement("EXPLAIN ANALYZE "+bound.getSql())) {
                    new DefaultParameterHandler(ms,parameters,bound).setParameters(statement);
                    try(var rs=statement.executeQuery()){while(rs.next())lines.add(rs.getString(1));}
                }
                planRows.add(Map.of("user",user,"assignments",assignments.size(),"sql",bound.getSql(),"plan",lines));
            }
            result.put("plans",planRows);
            result.put("indexes",f.jdbc.queryForList("SHOW INDEX FROM rbac_offline_operation"));
            result.put("resources",f.jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Bytes_sent','Bytes_received','Threads_running','Created_tmp_disk_tables')"));
            var output=Path.of("target/rbac-proof-p11/performance-"+phase+".json");Files.createDirectories(output.getParent());Files.writeString(output,RbacJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } finally {server.stop(0);pool.shutdownNow();f.close();}
    }
    private static void restartOwnedDatabase(javax.sql.DataSource source)throws Exception {
        String container=System.getenv("RBAC_PERF_MYSQL_CONTAINER");assertNotNull(container);assertTrue(container.matches("codex-p1-mysql-[0-9a-f]{8}"));
        var inspect=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",container).start();
        assertEquals("rbac-proof-p11",RbacJson.mapper().readTree(inspect.getInputStream()).path("codex.task").asText());assertEquals(0,inspect.waitFor());
        var restart=new ProcessBuilder("docker","restart",container).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(restart.waitFor(30,TimeUnit.SECONDS));assertEquals(0,restart.exitValue());
        // Docker Desktop may allocate a new random host port on restart.
        var port=new ProcessBuilder("docker","port",container,"3306").start();String address=new String(port.getInputStream().readAllBytes()).trim();assertEquals(0,port.waitFor());assertTrue(address.matches("127[.]0[.]0[.]1:[0-9]+"));
        var ds=(org.springframework.jdbc.datasource.DriverManagerDataSource)source;
        ds.setUrl(Objects.requireNonNull(ds.getUrl()).replaceFirst("127[.]0[.]0[.]1:[0-9]+",address));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(true) {
            try(var connection=source.getConnection()){break;}
            catch(java.sql.SQLException e){if(System.nanoTime()>deadline)throw e;Thread.sleep(200);}
        }
    }
}
