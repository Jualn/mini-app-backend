package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.MiniAppApplication;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.MiniSessionResponse;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.jualn.miniapp.module.authorization.service.RbacOfflineTool.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full application and real QR/Sa/Redis/HTTP. Only external WeChat and release support use fixtures. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/rbac_offline_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacOfflineOnlineHttpTest {
    static final ObjectMapper JSON=RbacJson.mapper();
    static final AtomicReference<String> SCENE=new AtomicReference<>(), OMIT_SUPPORT=new AtomicReference<>("");
    static final long A=920001,B=920002,LEGACY=920003,C=920004,D=920005,TARGET=920006;
    static final String AUTH="/v1/admin/authorization",CAP=AUTH+"/me/capabilities";
    static final Path DIR=Path.of("target/rbac-offline-online");
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String url,base; JdbcTemplate jdbc; RbacOfflineTool offline;
    ServletWebServerApplicationContext app;
    KeyPair first,second;
    final List<Map<String,Object>> wire=new ArrayList<>(),steps=new ArrayList<>();
    final List<String> issued=new ArrayList<>();
    String phase="setup";
    @BeforeAll void start() throws Exception {
        url=System.getProperty("event.test.jdbcUrl"); requireSyntheticUrl(url);
        String redis=System.getenv("RBAC_ONLINE_REDIS_PORT");
        if(redis==null||!redis.matches("[0-9]+"))throw new IllegalStateException("Owned Redis port required");
        Files.createDirectories(DIR);
        String options="?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=2500&connectTimeout=1000";
        var admin=new JdbcTemplate(new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/'))+"/mysql"+options,"root",""));
        admin.execute("CREATE DATABASE rbac_offline_test");
        Flyway.configure().dataSource(url+options,"root","").load().migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+options,"root",""));
        for(long id=A;id<=920008;id++){
            jdbc.update("INSERT INTO user_profile(id,openid,nickname,role,status) VALUES(?,?,?,?,1)",id,"offline-online-"+id,"SYNTHETIC "+id,id==LEGACY?3:1);
            jdbc.update("INSERT INTO rbac_authorization_authority VALUES(?,'LEGACY',UUID(),UTC_TIMESTAMP(6))",id);
        }
        first=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();second=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jdbc.execute("CREATE TABLE rbac_offline_test_guard(id INT PRIMARY KEY,environment_id VARCHAR(96),executor_ref VARCHAR(128),approver_one VARCHAR(128),key_one_sha256 CHAR(64),approver_two VARCHAR(128),key_two_sha256 CHAR(64))");
        jdbc.update("INSERT INTO rbac_offline_test_guard VALUES(1,'synthetic-online','synthetic-executor','one',?,'two',?)",RbacCatalog.hash(first.getPublic().getEncoded()),RbacCatalog.hash(second.getPublic().getEncoded()));
        for(String role:List.of("ROOT","NOTICE_MONITOR")){
            jdbc.update("UPDATE rbac_permission_definition p JOIN rbac_role_permission m ON p.code=m.permission_code SET p.lifecycle='ENABLED',p.readiness='READY',p.grantable=1,p.unavailable_reason=NULL WHERE m.role_code=?",role);
            jdbc.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code=?",role);
        }
        offline=RbacOfflineTool.connect(url,"root","");
        phase="BOOTSTRAP_OFFLINE";var bootstrap=cli(command(Kind.BOOTSTRAP,null,A,B));
        assertEquals(2,bootstrap.path("effects").size());assertEquals(0,count("rbac_authorization_change"));
        assertEquals(2,count("rbac_offline_effect"));
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(MiniAppApplication.class,Fixture.class).run(
                "--spring.profiles.active=rbac-offline-online","--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url="+url+options,"--spring.datasource.username=root","--spring.datasource.password=",
                "--spring.datasource.hikari.connection-timeout=2000","--spring.datasource.hikari.minimum-idle=1",
                "--spring.data.redis.host=127.0.0.1","--spring.data.redis.port="+redis,"--spring.data.redis.password=","--spring.data.redis.database=0",
                "--spring.data.redis.timeout=1200ms","--spring.data.redis.connect-timeout=1200ms",
                "--spring.flyway.enabled=false","--sa-token.jwt-secret-key=synthetic-offline-online-isolation-20261016","--sa-token.is-log=false",
                "--logging.level.root=WARN","--admin-auth.qr-login-v2.env-version=develop",
                "--admin-auth.qr-login-v2.create-ip-limit=200","--admin-auth.qr-login-v2.create-global-limit=300",
                "--cos.secret-id=synthetic-id","--cos.secret-key=synthetic-key","--cos.bucket=synthetic-online-1250000000",
                "--cos.region=ap-guangzhou","--cos.public-url-prefix=https://synthetic.invalid","--cos.custom-domain=https://synthetic.invalid","--cos.presign-expire-seconds=3600");
        base="http://127.0.0.1:"+app.getWebServer().getPort();
        assertTrue(SaManager.getSaTokenDao().getClass().getName().contains("Redis"));
        assertFalse(app.getBeansOfType(RbacOfflineTool.class).size()>0);
        step("full application",Map.of("port",app.getWebServer().getPort(),"redisPort",redis,"dao",SaManager.getSaTokenDao().getClass().getName(),"reader",app.getBean(RbacAuthorizationReader.class).getClass().getName()));
    }
    @AfterAll void stop() throws Exception {
        try {
            if(app!=null){for(String token:issued){try{call("POST","/v1/admin/auth/logout",token,null,Map.of());}catch(Exception ignored){}}}
            Files.writeString(DIR.resolve("http.json"),JSON.writeValueAsString(wire));
            Files.writeString(DIR.resolve("steps.json"),JSON.writeValueAsString(steps));
            if(jdbc!=null){Files.writeString(DIR.resolve("facts.json"),JSON.writeValueAsString(Map.of(
                    "offlineOperations",jdbc.queryForList("SELECT id,kind,environment_id,executor_ref,snapshot_version FROM rbac_offline_operation"),
                    "effects",jdbc.queryForList("SELECT id,operation_id,user_id,assignment_id FROM rbac_offline_effect"),
                    "assignments",jdbc.queryForList("SELECT id,user_id,role_code,namespace,version,grant_change_id,revoke_change_id,revoked_at FROM rbac_user_role_assignment"),
                    "changes",jdbc.queryForList("SELECT id,actor_user_id,target_user_id,assignment_id,action,operation_id FROM rbac_authorization_change"))));}
        } finally {if(app!=null)app.close();OMIT_SUPPORT.set("");}
    }
    @Test void offlineFactsAreConsumedByFullHttpAuthenticationGovernanceAndAudit() throws Exception {
        phase="BOOTSTRAP_ONLINE";
        String a=qr(A,"RBAC_V1"),b=qr(B,"RBAC_V1");
        for(String root:List.of(a,b)){
            JsonNode cap=expect(200,"GET",CAP,root,null);
            assertEquals(4,cap.path("grants").get(0).path("permissionCodes").size());
            expect(403,"GET","/v1/admin/system/overview",root,null);
            expect(403,"GET","/v1/admin/notification-plans",root,null);
        }
        expect(200,"GET",AUTH+"/permissions",a,null);expect(200,"GET",AUTH+"/roles",a,null);expect(200,"GET",AUTH+"/roles/ROOT",a,null);
        String oldRootA=assignment(A,"ROOT");String oldRootB=assignment(B,"ROOT");
        JsonNode details=expect(200,"GET",assign(A)+"/"+oldRootA,b,null);
        assertEquals(jdbc.queryForObject("SELECT id FROM rbac_offline_effect WHERE assignment_id=?",String.class,oldRootA),details.path("assignment").path("grantChangeId").asText());
        expect(200,"GET",assign(A),b,null); assertEquals(0,expect(200,"GET",AUTH+"/changes",a,null).path("items").size());

        phase="SOURCE_SWITCH_WINDOW";
        String ordinaryLegacy=ordinary(LEGACY),legacyToken=qr(LEGACY,"LEGACY");
        expect(200,"GET","/v1/admin/auth/me",legacyToken,null);
        expect(200,"GET","/v1/admin/system/overview",legacyToken,null);
        expect(200,"GET","/v1/reports/admin",ordinaryLegacy,null);
        // A test-only JDBC wrapper calls the real server commit, then interrupts the standalone CLI with a lost acknowledgement.
        var switchRequest=command(Kind.SOURCE_SWITCH,A,LEGACY); cli(switchRequest,true);
        assertEquals("ASSIGNMENT",jdbc.queryForObject("SELECT source FROM rbac_authorization_authority WHERE user_id=?",String.class,LEGACY));
        JsonNode restored=cli(switchRequest);assertTrue(restored.path("effects").get(0).path("assignmentId").isNull());assertEquals(2,count("rbac_offline_operation"));
        app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).opsForValue().set(
                cn.jualn.miniapp.common.constant.RedisKeyConstant.USER_AUTH_PROFILE_PREFIX+LEGACY,"{\"role\":3,\"status\":1}",Duration.ofMinutes(2));
        assertEquals(cn.jualn.miniapp.module.user.enums.UserRole.ADMIN,cn.dev33.satoken.stp.StpUtil.getStpLogic().getSessionByLoginId(LEGACY).get("role"));
        expect(200,"GET",CAP,legacyToken,null);assertFalse(expect(200,"GET",CAP,legacyToken,null).path("canUseAdmin").asBoolean());
        expect(401,"GET","/v1/admin/system/overview",legacyToken,null);
        expect(403,"GET","/v1/reports/admin",ordinaryLegacy,null);
        assertEquals(3,jdbc.queryForObject("SELECT role FROM user_profile WHERE id=?",Integer.class,LEGACY));
        step("no-grant window",Map.of("source","ASSIGNMENT","ordinaryRole",3,"canUseAdmin",false,"duration","until explicit normal HTTP grant"));

        phase="SOURCE_SWITCH_HTTP_GRANT_REVOKE";
        String key="online-legacy-grant-0001";Map<String,Object> body=grantBody(a,LEGACY,"NOTICE_MONITOR","NOTIFICATION");
        JsonNode granted=write(201,assign(LEGACY),a,key,body);
        assertEquals(granted,write(201,assign(LEGACY),a,key,body));
        expect(200,"GET","/v1/admin/notification-plans",legacyToken,null);
        expect(403,"GET","/v1/admin/system/overview",legacyToken,null);
        expect(409,"GET","/v1/admin/auth/me",legacyToken,null);
        String assignedToken=qr(LEGACY,"RBAC_V1");
        assertEquals("NOTIFICATION",expect(200,"GET",CAP,assignedToken,null).path("grants").get(0).path("namespace").asText());
        JsonNode revoked=revoke(a,LEGACY,granted.path("assignment").path("assignmentId").asText(),"online-legacy-revoke-0001");
        expect(401,"GET","/v1/admin/notification-plans",legacyToken,null);expect(401,"GET","/v1/admin/system/overview",assignedToken,null);
        expect(403,"GET","/v1/reports/admin",ordinaryLegacy,null);
        assertFalse(expect(200,"GET",CAP,assignedToken,null).path("canUseAdmin").asBoolean());
        assertEquals(granted,write(201,assign(LEGACY),a,key,body));
        assertFalse(expect(200,"GET",CAP,legacyToken,null).path("canUseAdmin").asBoolean());
        var duplicate=grantBody(a,LEGACY,"NOTICE_MONITOR","NOTIFICATION");
        JsonNode system=write(201,assign(LEGACY),a,"online-other-grant-0001",duplicate);
        write(409,assign(LEGACY),a,"online-duplicate-new-key",duplicate);
        revoke(a,LEGACY,system.path("assignment").path("assignmentId").asText(),"online-other-revoke-001");
        expect(200,"GET",AUTH+"/changes?targetUserId="+LEGACY,a,null);

        phase="OFFLINE_ROOT_REVOKE_AND_RECOVERY";
        // B creates a retained ordinary receipt; revoke A's actual offline-root assignment over normal HTTP.
        cli(command(Kind.SOURCE_SWITCH,A,TARGET));
        Map<String,Object> savedBody=grantBody(b,TARGET,"NOTICE_MONITOR","SYSTEM");
        JsonNode savedGrant=write(201,assign(TARGET),b,"online-retained-original-key",savedBody);
        revoke(b,TARGET,savedGrant.path("assignment").path("assignmentId").asText(),"online-retained-revoke");
        JsonNode rootRevoke=revoke(b,A,oldRootA,"online-root-a-revoke-001");
        assertEquals(details.path("assignment").path("grantChangeId"),rootRevoke.path("assignment").path("grantChangeId"));
        assertFalse(expect(200,"GET",CAP,a,null).path("canUseAdmin").asBoolean());
        expect(403,"GET",AUTH+"/roles",a,null);
        // A fresh independent ROOT's zero-root precondition is constructed only on this disposable schema.
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=?",B);
        expect(401,"GET",CAP,b,null);
        String revokedBefore=jdbc.queryForObject("SELECT CAST(JSON_OBJECT('revokedAt',revoked_at,'revokeChangeId',revoke_change_id,'version',version) AS CHAR) FROM rbac_user_role_assignment WHERE id=?",String.class,oldRootA);
        JsonNode recovery=cli(command(Kind.RECOVERY,null,C,D));assertEquals(2,recovery.path("effects").size());
        String c=qr(C,"RBAC_V1"),d=qr(D,"RBAC_V1");
        for(String token:List.of(c,d))assertTrue(expect(200,"GET",CAP,token,null).path("canUseAdmin").asBoolean());
        expect(200,"GET",assign(C)+"/"+assignment(C,"ROOT"),d,null);
        assertEquals(revokedBefore,jdbc.queryForObject("SELECT CAST(JSON_OBJECT('revokedAt',revoked_at,'revokeChangeId',revoke_change_id,'version',version) AS CHAR) FROM rbac_user_role_assignment WHERE id=?",String.class,oldRootA));
        assertFalse(expect(200,"GET",CAP,a,null).path("canUseAdmin").asBoolean());
        expect(200,"GET",assign(A)+"/"+oldRootA,c,null);
        JsonNode current=write(201,assign(LEGACY),c,"online-recovered-root-grant",grantBody(c,LEGACY,"NOTICE_MONITOR","NOTIFICATION"));
        revoke(d,LEGACY,current.path("assignment").path("assignmentId").asText(),"online-recovered-root-revoke");
        // Bring B's account back only as a synthetic failure-fixture repair; its previous token remains revoked.
        jdbc.update("UPDATE user_profile SET status=1 WHERE id=?",B);expect(401,"GET",CAP,b,null);
        String renewedB=qr(B,"RBAC_V1");
        assertEquals(savedGrant,write(201,assign(TARGET),renewedB,"online-retained-original-key",savedBody));
        assertEquals("REVOKED",expect(200,"GET",assign(TARGET)+"/"+savedGrant.path("assignment").path("assignmentId").asText(),c,null).path("assignment").path("status").asText());
        JsonNode audit=expect(200,"GET",AUTH+"/changes?pageSize=100",c,null);
        assertEquals(count("rbac_authorization_change"),audit.path("items").size());
        assertTrue(audit.path("items").findValuesAsText("action").stream().allMatch(x->Set.of("GRANT","REVOKE").contains(x)));

        phase="NEGATIVE_CATALOG_AND_CURRENT_ROOT";
        jdbc.update("DELETE FROM rbac_role_permission WHERE role_code='ROOT' AND permission_code='audit-log:read'");
        try{expect(503,"GET",CAP,c,null);}finally{jdbc.update("INSERT INTO rbac_role_permission VALUES('ROOT','audit-log:read')");}
        OMIT_SUPPORT.set("audit-log:read");try{assertFalse(expect(200,"GET",CAP,c,null).path("canUseAdmin").asBoolean());expect(403,"GET",AUTH+"/roles",c,null);}finally{OMIT_SUPPORT.set("");}
        jdbc.update("UPDATE user_profile SET status=3 WHERE id=?",D);expect(401,"GET",CAP,d,null);jdbc.update("UPDATE user_profile SET status=1 WHERE id=?",D);expect(401,"GET",CAP,d,null);

        phase="NEGATIVE_OFFLINE_AUDIT_REFERENCE";
        String active=assignment(C,"ROOT");Map<String,Object> fact=jdbc.queryForMap("SELECT * FROM rbac_offline_effect WHERE assignment_id=?",active);
        jdbc.update("DELETE FROM rbac_offline_effect WHERE assignment_id=?",active);
        try{expect(503,"GET",CAP,c,null);expect(503,"GET",assign(C)+"/"+active,renewedB,null);}finally{jdbc.update("INSERT INTO rbac_offline_effect(id,operation_id,user_id,assignment_id,before_snapshot,after_snapshot) VALUES(?,?,?,?,?,?)",fact.get("id"),fact.get("operation_id"),fact.get("user_id"),fact.get("assignment_id"),fact.get("before_snapshot"),fact.get("after_snapshot"));}
        jdbc.update("UPDATE rbac_offline_effect SET after_snapshot='{}' WHERE assignment_id=?",active);
        try{expect(503,"GET",CAP,c,null);expect(503,"GET",assign(C)+"/"+active,renewedB,null);}finally{jdbc.update("UPDATE rbac_offline_effect SET after_snapshot=? WHERE assignment_id=?",fact.get("after_snapshot"),active);}
        expect(200,"GET",CAP,c,null);

        phase="NEGATIVE_DATABASE_AUTHORITY_READ";
        jdbc.execute("RENAME TABLE rbac_authorization_authority TO rbac_authorization_authority_fault");
        try{expect(503,"GET",CAP,c,null);}finally{jdbc.execute("RENAME TABLE rbac_authorization_authority_fault TO rbac_authorization_authority");}
        expect(200,"GET",AUTH+"/permissions",c,null);
        step("COMPLETE",Map.of("ordinaryChanges",count("rbac_authorization_change"),"offlineOperations",count("rbac_offline_operation"),"offlineEffects",count("rbac_offline_effect")));
    }
    String assign(long user){return AUTH+"/users/"+user+"/role-assignments";}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    String assignment(long user,String role){return jdbc.queryForObject("SELECT id FROM rbac_user_role_assignment WHERE user_id=? AND role_code=? AND revoked_at IS NULL ORDER BY granted_at DESC LIMIT 1",String.class,user,role);}
    void step(String label,Object value){steps.add(Map.of("phase",phase,"label",label,"value",value));}
    Map<String,Object> grantBody(String actor,long user,String role,String space)throws Exception{
        return Map.of("roleCode",role,"namespace",space,"scope",Map.of("type","GLOBAL"),"reason","synthetic offline to online approval",
                "expectedAuthorizationVersion",expect(200,"GET",assign(user),actor,null).path("authorizationVersion").asText());
    }
    JsonNode revoke(String actor,long user,String id,String key)throws Exception{
        JsonNode detail=expect(200,"GET",assign(user)+"/"+id,actor,null);
        return write(200,assign(user)+"/"+id+":revoke",actor,key,Map.of("reason","synthetic withdrawal","expectedAuthorizationVersion",detail.path("authorizationVersion").asText(),"expectedAssignmentVersion",detail.path("assignment").path("assignmentVersion").asText()));
    }
    JsonNode write(int status,String path,String auth,String key,Object body)throws Exception{return checked(status,call("POST",path,auth,body,Map.of("Idempotency-Key",key)));}
    JsonNode expect(int status,String method,String path,String token,Object body)throws Exception{return checked(status,call(method,path,token,body,Map.of()));}
    JsonNode checked(int status,HttpResponse<String> r)throws Exception{assertEquals(status,r.statusCode(),phase+" "+r.uri().getPath()+" "+(r.uri().getPath().startsWith(AUTH)?r.body():"<non-RBAC body redacted>"));return JSON.readTree(r.body());}
    HttpResponse<String> call(String method,String path,String token,Object body,Map<String,String> headers)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));if(token!=null)b.header("Authorization","Bearer "+token);headers.forEach(b::header);
        if(body!=null)b.header("Content-Type","application/json");
        var r=HTTP.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        var record=new LinkedHashMap<String,Object>();record.put("phase",phase);record.put("method",method);record.put("path",path);record.put("status",r.statusCode());
        record.put("contentType",r.headers().firstValue("Content-Type").orElse(""));record.put("cacheControl",r.headers().firstValue("Cache-Control").orElse(""));record.put("location",r.headers().firstValue("Location").orElse(""));
        if(path.startsWith(AUTH)){record.put("body",JSON.readTree(r.body()));if(body!=null)record.put("requestBody",body);if(headers.containsKey("Idempotency-Key"))record.put("keyHash",RbacCatalog.hash(headers.get("Idempotency-Key").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        else{record.put("body","<credentials and identity response redacted>");}
        wire.add(record);return r;
    }
    String ordinary(long user)throws Exception{return expect(200,"POST","/v1/auth/login",null,Map.of("code","online-"+user)).path("data").path("token").asText();}
    String qr(long user,String representation)throws Exception{
        String ordinary=ordinary(user);JsonNode created=expect(201,"POST","/v1/admin/auth/qr-login-sessions",null,null);
        String session=created.path("session").path("sessionId").asText(),secret=created.path("pollSecret").asText(),scene=SCENE.get();
        expect(200,"POST","/v1/admin/auth/qr-login-scans",ordinary,Map.of("sceneCode",scene));expect(200,"POST","/v1/admin/auth/qr-login-scans:confirm",ordinary,Map.of("sceneCode",scene));
        var consumed=call("POST","/v1/admin/auth/qr-login-sessions/"+session+":consume",null,null,Map.of("X-Admin-Login-Secret",secret,"X-Admin-Identity-Representation",representation));
        String token=checked(200,consumed).path("token").asText();assertFalse(token.isBlank());issued.add(token);return token;
    }
    SignedRequest command(Kind kind,Long sponsor,Long... users)throws Exception{
        var info=offline.inspect(List.of(users));
        var request=new Request(1,UUID.randomUUID().toString(),info.environmentId(),kind,"synthetic-executor","synthetic-online-approval",RbacCatalog.hash("synthetic evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)),Instant.now().plusSeconds(1200).toString(),info.catalogVersion(),sponsor,sponsor==null?null:jdbc.queryForObject("SELECT version FROM rbac_authorization_authority WHERE user_id=?",String.class,sponsor),info.targets());
        var approvals=new ArrayList<Approval>();for(var entry:List.of(Map.entry("one",first),Map.entry("two",second))){Signature s=Signature.getInstance("Ed25519");s.initSign(entry.getValue().getPrivate());s.update(signingBytes(request));approvals.add(new Approval(entry.getKey(),Base64.getEncoder().encodeToString(entry.getValue().getPublic().getEncoded()),Base64.getEncoder().encodeToString(s.sign())));}
        return new SignedRequest(request,approvals);
    }
    JsonNode cli(SignedRequest request)throws Exception{return cli(request,false);}
    JsonNode cli(SignedRequest request,boolean loseCommitAck)throws Exception{
        Path file=Files.createTempFile(DIR,"signed-synthetic-",".json"),output=Files.createTempFile(DIR,"cli-",".log");
        try{
            Files.writeString(file,encode(request));var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),loseCommitAck?LoseCommitAckMain.class.getName():RbacOfflineTool.class.getName(),url,file.toAbsolutePath().toString(),"--execute").redirectErrorStream(true).redirectOutput(output.toFile());
            process.environment().put("RBAC_OFFLINE_DB_USER","root");process.environment().put("RBAC_OFFLINE_DB_PASSWORD","");
            Process child=process.start();try{assertTrue(child.waitFor(25,TimeUnit.SECONDS));
                if(loseCommitAck){assertNotEquals(0,child.exitValue());assertTrue(Files.readString(output).contains("synthetic commit acknowledgement lost"));Files.copy(output,DIR.resolve("source-switch-unknown.log"),StandardCopyOption.REPLACE_EXISTING);step("SOURCE_SWITCH UNKNOWN_OUTCOME",Map.of("operationId",request.request().operationId(),"childExit",child.exitValue(),"fault","real commit then simulated JDBC acknowledgement loss"));return null;}
                assertEquals(0,child.exitValue(),Files.readString(output));}finally{if(child.isAlive())child.destroyForcibly();}
            String json=Files.readAllLines(output).stream().filter(l->l.startsWith("{")).reduce((a,b)->b).orElseThrow();JsonNode result=JSON.readTree(json);step("CLI committed/replayed",result);return result;
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(output);}
    }
    /** Test launcher only: the actual CLI/guard/signature/transaction implementation is unchanged. */
    public static class LoseCommitAckMain{
        public static void main(String[] args)throws Exception{
            Driver mysql=(Driver)Class.forName("com.mysql.cj.jdbc.Driver").getConstructor().newInstance();
            var registered=Collections.list(DriverManager.getDrivers());for(Driver driver:registered)DriverManager.deregisterDriver(driver);
            var lose=new AtomicBoolean(true);
            DriverManager.registerDriver(new Driver(){
                public Connection connect(String u,Properties p)throws SQLException{
                    Connection connection=mysql.connect(u,p);if(connection==null)return null;
                    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,values)->{
                        try{Object value=method.invoke(connection,values);if(method.getName().equals("commit")&&lose.getAndSet(false))throw new SQLException("synthetic commit acknowledgement lost");return value;}
                        catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
                public boolean acceptsURL(String u)throws SQLException{return mysql.acceptsURL(u);}
                public DriverPropertyInfo[] getPropertyInfo(String u,Properties p)throws SQLException{return mysql.getPropertyInfo(u,p);}
                public int getMajorVersion(){return mysql.getMajorVersion();}public int getMinorVersion(){return mysql.getMinorVersion();}
                public boolean jdbcCompliant(){return mysql.jdbcCompliant();}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getLogger("synthetic-offline");}
            });
            RbacOfflineTool.main(args);
        }
    }
    @Configuration(proxyBeanMethods=false) static class Fixture{
        @Bean @Primary RbacCatalog onlineCatalog(){return new RbacCatalog(SYNTHETIC_SUPPORT){
            @Override boolean permissionReady(String code,Map<String,RbacModels.Definition> rows){return !code.equals(OMIT_SUPPORT.get())&&super.permissionReady(code,rows);}
            @Override String runtimeVersion(List<RbacModels.Definition> ps,List<RbacModels.Definition> rs){String v=super.runtimeVersion(ps,rs);return OMIT_SUPPORT.get().isEmpty()?v:RbacCatalog.hash((v+OMIT_SUPPORT.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        };}
        @Bean @Primary WxClient onlineWx(){
            WxClient wx=mock(WxClient.class);when(wx.getMiniSession(anyString())).thenAnswer(i->{String code=i.getArgument(0);if(!code.matches("online-92000[1-8]"))throw new IllegalArgumentException("Synthetic only");var r=new MiniSessionResponse();r.setOpenid("offline-online-"+code.substring(7));r.setSessionKey("synthetic-only");return r;});
            when(wx.generateMiniProgramCode(anyString(),anyString(),anyString(),anyBoolean())).thenAnswer(i->{SCENE.set(i.getArgument(1));var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",bytes);return bytes.toByteArray();});return wx;
        }
    }
}
