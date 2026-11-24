package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
/** Owned MySQL/Redis/full HTTP/real Duty writer and Reader; only external providers are doubles. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="points.integration.jdbcUrl",
        matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_integration_test_[a-z0-9]+")
class ContentSafetyCrossHttpTest extends PointsIntegrationHttpTest {
 @Override @BeforeAll void start() throws Exception {System.setProperty("unified.r3.fixture","EXPLICIT_SYNTHETIC_R3_APPROVAL");super.start();}
 @Override @AfterAll void stop() throws Exception {
  try {if(db!=null) {Files.createDirectories(Path.of("target/safety-cross-evidence"));JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/safety-cross-evidence/final-facts.json").toFile(),Map.of("rounds",db.queryForList("SELECT * FROM content_safety_review"),"checks",db.queryForList("SELECT * FROM content_safety_check"),"posts",db.queryForList("SELECT id,status,audit_status,revision FROM post"),"comments",db.queryForList("SELECT id,status,audit_status FROM comment")));}}
  finally {super.stop();System.clearProperty("unified.r3.fixture");}
 }
 JsonNode data(JsonNode n){return n.has("data")?n.path("data"):n;}
 long createPost(String text) throws Exception {return data(checked(200,call("POST","/v1/post",user,Map.of("title","Integration safety","content",text),Map.of()))).path("id").asLong();}
 long task(long target) {return db.queryForObject("SELECT task_id FROM content_safety_review WHERE target_id=? ORDER BY task_id DESC LIMIT 1",Long.class,target);}
 void awaitState(long task,String state) throws Exception {for(int i=0;i<160;i++){if(state.equals(db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,task)))return;Thread.sleep(250);}fail("State not reached "+task+" "+state);}
 JsonNode detail(long t,String token) throws Exception {return data(expect(200,"GET","/v1/admin/reviews/"+t,token,null));}
 @Test void crossAcceptance() throws Exception {
  when(app.getBean(WxClient.class).msgSecCheck(any())).thenAnswer(i->{var response=new WxMsgSecCheckResponse();response.setErrcode(0);var r=new WxMsgSecCheckResponse.ResultInfo();r.setSuggest("pass");response.setResult(r);response.setVerifiedPassStructure(true);return response;});
  long a=930120,b=930121,c=930122;syntheticUser(a,true);syntheticUser(b,true);syntheticUser(c,true);
  var grant=duty(a,"POST_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));
  duty(b,"ACTIVITY_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));
  duty(c,"PUBLIC_EVENT_COMMENT_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));
  String ta=qr(a),tb=qr(b),tc=qr(c);
  long standalone=930123;syntheticUser(standalone,true);
  var assignmentVersion=expect(200,"GET",AUTH+"/users/"+standalone+"/role-assignments",root,null).path("authorizationVersion").asText();
  var stand=call("POST",AUTH+"/users/"+standalone+"/role-assignments",root,Map.of("roleCode","CONTENT_REVIEWER","namespace","POST","scope",Map.of("type","GLOBAL"),"reason","Standalone cannot bypass duty","expectedAuthorizationVersion",assignmentVersion),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
  assertEquals(405,stand.statusCode(),stand.body());
  long publicPost=createPost("pass baseline");awaitState(task(publicPost),"approved");
  checked(200,call("GET","/v1/post/"+publicPost,outsider,null,Map.of()));
  var editable=call("GET","/v1/post/"+publicPost+"?view=edit",user,null,Map.of());checked(200,editable);
  checked(200,call("PATCH","/v1/post/"+publicPost,user,Map.of("content","edited checked text"),Map.of("If-Match",editable.headers().firstValue("ETag").orElseThrow())));
  assertEquals("edited checked text",db.queryForObject("SELECT content FROM post WHERE id=?",String.class,publicPost));
  assertTrue(detail(task(publicPost),ta).path("content").asText().contains("pass baseline"));
  when(app.getBean(WxClient.class).msgSecCheck(any())).thenAnswer(i->{var response=new WxMsgSecCheckResponse();response.setErrcode(0);var r=new WxMsgSecCheckResponse.ResultInfo();r.setSuggest("risky");response.setResult(r);return response;});
  var beforeRejectedEdit=call("GET","/v1/post/"+publicPost+"?view=edit",user,null,Map.of());checked(200,beforeRejectedEdit);
  checked(422,call("PATCH","/v1/post/"+publicPost,user,Map.of("content","rejected candidate"),Map.of("If-Match",beforeRejectedEdit.headers().firstValue("ETag").orElseThrow())));
  assertEquals("edited checked text",db.queryForObject("SELECT content FROM post WHERE id=?",String.class,publicPost));
  long post=createPost("risk requiring human"),t=task(post);awaitState(t,"pending");
  var queue=data(expect(200,"GET","/v1/admin/reviews?tab=pending",ta,null));
  assertTrue(queue.path("summary").path("oldestWaitingMinutes").asLong()<3,"New submissions cannot appear eight hours old under UTC JDBC sessions");
  var storedTime=db.queryForObject("SELECT created_at FROM content_safety_review WHERE task_id=?",java.time.LocalDateTime.class,t);
  assertTrue(java.time.Duration.between(storedTime,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))).abs().toMinutes()<3);
  var d=detail(t,ta);assertEquals("post",d.path("review").path("scene").asText());assertTrue(d.path("evidence").path("complete").asBoolean());
  expect(403,"GET","/v1/admin/reviews/"+t,tb,null);expect(403,"GET","/v1/admin/reviews/"+t,tc,null);
  for(String token:List.of(tb,tc)) {var list=data(expect(200,"GET","/v1/admin/reviews?tab=all",token,null));assertEquals(0,list.path("total").asInt());assertEquals(0,list.path("summary").path("pending").asInt());}
  var body=Map.of("action","approve");var key=Map.of("Idempotency-Key",UUID.randomUUID().toString());
  checked(200,call("POST","/v1/admin/reviews/"+t+"/decisions",ta,body,key));checked(200,call("POST","/v1/admin/reviews/"+t+"/decisions",ta,body,key));
  assertEquals(2,db.queryForObject("SELECT status FROM post WHERE id=?",Integer.class,post));
  checked(200,call("GET","/v1/post/"+post,outsider,null,Map.of()));
  long comment=data(checked(200,call("POST","/v1/comment",user,Map.of("targetType","POST","targetId",publicPost,"content","risk comment"),Map.of()))).asLong();
  long ct=task(comment);awaitState(ct,"pending");assertEquals("post-comment",detail(ct,ta).path("review").path("scene").asText());
  checked(200,call("POST","/v1/admin/reviews/"+ct+"/decisions",ta,Map.of("action","reject","reasonCode","other","remark","integration rejected"),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  assertEquals("rejected",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,ct));
  long race=createPost("concurrent reviewers"),rt=task(race);awaitState(rt,"pending");duty(b,"POST_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));
  var pool=Executors.newFixedThreadPool(2);var latch=new CountDownLatch(1);try {var one=pool.submit(()->{latch.await();return call("POST","/v1/admin/reviews/"+rt+"/decisions",ta,body,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode();});var two=pool.submit(()->{latch.await();return call("POST","/v1/admin/reviews/"+rt+"/decisions",tb,body,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode();});latch.countDown();var statuses=new ArrayList<>(List.of(one.get(),two.get()));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);}finally{pool.shutdownNow();}
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE review_task_id=? AND audit_source=2",Integer.class,rt));
  long revoke=createPost("revoked reviewer"),vt=task(revoke);awaitState(vt,"pending");detail(vt,ta);revokeDuty(grant.scopeGrantIds().get(0));var denied=call("POST","/v1/admin/reviews/"+vt+"/decisions",ta,body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertTrue(Set.of(401,403).contains(denied.statusCode()));assertEquals("pending",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,vt));
  if(Boolean.getBoolean("safety.browser")) {
   var dir=Path.of("target/safety-cross-evidence");Files.createDirectories(dir);
   Files.deleteIfExists(dir.resolve("browser-done.txt"));
   JSON.writeValue(dir.resolve("browser-session.json").toFile(),Map.of("base",base,"token",tb,"profile",Map.of("id",Long.toString(b),"displayName","Isolated reviewer","avatarText","审","authorizationModel","ASSIGNMENT")));
   try {for(int i=0;i<720 && !Files.exists(dir.resolve("browser-done.txt"));i++)Thread.sleep(250);assertTrue(Files.exists(dir.resolve("browser-done.txt")),"Browser must run during live app");}
   finally {Files.deleteIfExists(dir.resolve("browser-session.json"));}
   assertEquals("approved",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,vt));
  }
  Files.createDirectories(Path.of("target/safety-cross-evidence"));JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/safety-cross-evidence/http.json").toFile(),wire);
 }
}
