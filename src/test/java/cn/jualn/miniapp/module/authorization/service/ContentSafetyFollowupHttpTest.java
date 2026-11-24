package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.nio.file.*;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckResponse;
/** Real HTTP, real authorization writer/Reader and MySQL locks. Only external WeChat is doubled. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="points.integration.jdbcUrl",
        matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/points_integration_test_[a-z0-9]+")
class ContentSafetyFollowupHttpTest extends ContentSafetyCrossHttpTest {
 final List<Map<String,Object>> outcomes=new ArrayList<>();
 Map<String,String> key(){return Map.of("Idempotency-Key",UUID.randomUUID().toString());}
 UnifiedModels.Receipt grantUntil(long actor,String duty,Instant until) throws Exception {
  String authority=app.getBean(RbacAuthorizationReader.class).read(ROOT).grants().stream().filter(g->"ROOT".equals(g.roleCode())).findFirst().orElseThrow().assignmentId();
  var intent=new UnifiedModels.Intent("GRANT",Long.toString(actor),List.of(new UnifiedModels.Selection(duty,new UnifiedModels.Target("PLATFORM",null,null),until.toString(),null)),authority,null,null,"isolated followup expiry");
  var preview=write(200,"POST",AUTH+"/duty-previews",root,intent,Map.of());
  return JSON.treeToValue(write(200,"POST",AUTH+"/duty-changes",root,new UnifiedModels.Commit(preview.path("previewId").asText(),intent),Map.of()),UnifiedModels.Receipt.class);
 }
 void blocked(String table) throws Exception {
  String schema=url.substring(url.lastIndexOf('/')+1);
  for(int i=0;i<80;i++){if(db.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?",Integer.class,schema,table)>0)return;Thread.sleep(50);}fail("Expected real DB lock wait on "+table);
 }
 String revokePreview(String scope) throws Exception {
  var intent=new UnifiedModels.Intent("REVOKE",null,null,null,"SCOPE",scope,"concurrent actual revocation");
  return write(200,"POST",AUTH+"/duty-previews",root,intent,Map.of()).path("previewId").asText();
 }
 java.net.http.HttpResponse<String> revoke(String scope,String preview) throws Exception {
  return call("POST",AUTH+"/duty-changes",root,new UnifiedModels.Commit(preview,new UnifiedModels.Intent("REVOKE",null,null,null,"SCOPE",scope,"concurrent actual revocation")),key());
 }
 void noDecision(long t) {assertEquals("pending",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,t));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE review_task_id=? AND audit_source=2",Integer.class,t));}
 @Test void followupAcceptance() throws Exception {
  when(app.getBean(WxClient.class).msgSecCheck(any())).thenAnswer(i->{var r=new WxMsgSecCheckResponse();r.setErrcode(0);var result=new WxMsgSecCheckResponse.ResultInfo();result.setSuggest("risky");r.setResult(result);return r;});
  long a=930120,e=930121;syntheticUser(a,true);syntheticUser(e,true);
  duty(a,"ACTIVITY_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));duty(e,"PUBLIC_EVENT_COMMENT_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));String ta=qr(a),te=qr(e);
  for(boolean activity:List.of(true,false)) {
   String path=activity?"/v1/admin/activities":"/v1/admin/public-events",type=activity?"ACTIVITY":"EXAM",token=activity?ta:te;
   var draft=new HashMap<String,Object>();draft.put("title",activity?"Activity comment integration":"Public event comment integration");for(String name:List.of("timeline","sections","actions","contacts","attachments"))draft.put(name,List.of());draft.put("summary","Isolated full lifecycle fixture");if(activity){draft.put("registrationMode","NONE");draft.put("participantMode","INDIVIDUAL");draft.put("category","OTHER");draft.put("organizer","Test organizer");draft.put("audienceScope",Map.of("type","CAMPUS"));draft.put("audienceSummary","All test students");}else{draft.put("type","OTHER");draft.put("sourceName","Test source");draft.put("officialUrl","https://example.invalid/test");}
   var created=call("POST",path,root,draft,Map.of());var resource=checked(201,created);long parent=resource.path(activity?"activityId":"publicEventId").asLong();assertTrue(parent>0,resource.toString());
   checked(200,call("POST",path+"/"+parent+":publish",root,null,Map.of("If-Match",created.headers().firstValue("ETag").orElseThrow())));
   for(String action:List.of("approve","reject")) {
    long comment=data(checked(200,call("POST","/v1/comment",user,Map.of("targetType",type,"targetId",parent,"content",type+" "+action+" safety candidate"),Map.of()))).asLong();long t=task(comment);awaitState(t,"pending");
    var d=detail(t,token);assertEquals(activity?"activity-comment":"public-event-comment",d.path("review").path("scene").asText());assertTrue(d.path("evidence").path("complete").asBoolean());expect(403,"GET","/v1/admin/reviews/"+t,activity?te:ta,null);
    var body=new HashMap<String,Object>();body.put("action",action);if(action.equals("reject"))body.put("reasonCode","other");checked(200,call("POST","/v1/admin/reviews/"+t+"/decisions",token,body,key()));
    assertEquals(action.equals("approve")?"approved":"rejected",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,t));
    var publicList=data(expect(200,"GET","/v1/comment?targetType="+type+"&targetId="+parent,outsider,null));boolean visible=publicList.toString().contains(type+" "+action+" safety candidate");assertEquals(action.equals("approve"),visible,publicList.toString());
    outcomes.add(Map.of("case",type+"-"+action,"comment",comment,"task",t,"publicVisible",visible,"dbStatus",db.queryForObject("SELECT status FROM comment WHERE id=?",Integer.class,comment)));
   }
  }
  var executor=Executors.newFixedThreadPool(2);
  try {
   // Decision obtains its real authorization lease, then waits on the target; revocation must wait.
   long actor=930122;syntheticUser(actor,true);var grant=duty(actor,"POST_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));String token=qr(actor);
   long post=createPost("decision wins concurrent revocation"),t=task(post);awaitState(t,"pending");String preview=revokePreview(grant.scopeGrantIds().get(0));
   try(var lock=source(url).getConnection()) {lock.setAutoCommit(false);lock.createStatement().executeQuery("SELECT id FROM post WHERE id="+post+" FOR UPDATE");
    var decide=executor.submit(()->call("POST","/v1/admin/reviews/"+t+"/decisions",token,Map.of("action","approve"),key()));blocked("post");
    var revoke=executor.submit(()->revoke(grant.scopeGrantIds().get(0),preview));Thread.sleep(200);assertFalse(revoke.isDone());lock.commit();checked(200,decide.get(15,TimeUnit.SECONDS));checked(200,revoke.get(15,TimeUnit.SECONDS));
   }
   assertEquals("approved",db.queryForObject("SELECT state FROM content_safety_review WHERE task_id=?",String.class,t));assertTrue(Set.of(401,403).contains(call("POST","/v1/admin/reviews/"+t+"/decisions",token,Map.of("action","approve"),key()).statusCode()));outcomes.add(Map.of("case","decision-before-revoke","task",t,"result","one approved then authorization withdrawn"));
   // Revocation holds governance and waits on the real subject row; decision must then lose authorization.
   long second=930123;syntheticUser(second,true);var g2=duty(second,"POST_SAFETY_REVIEW",new UnifiedModels.Target("PLATFORM",null,null));String tk2=qr(second);long p2=createPost("revocation wins"),t2=task(p2);awaitState(t2,"pending");String pr2=revokePreview(g2.scopeGrantIds().get(0));
   try(var lock=source(url).getConnection()) {lock.setAutoCommit(false);lock.createStatement().executeQuery("SELECT id FROM user_profile WHERE id="+second+" FOR UPDATE");
    var revoke=executor.submit(()->revoke(g2.scopeGrantIds().get(0),pr2));blocked("user_profile");var decide=executor.submit(()->call("POST","/v1/admin/reviews/"+t2+"/decisions",tk2,Map.of("action","approve"),key()));Thread.sleep(200);assertFalse(decide.isDone());lock.commit();checked(200,revoke.get(15,TimeUnit.SECONDS));assertTrue(Set.of(401,403).contains(decide.get(15,TimeUnit.SECONDS).statusCode()));
   }noDecision(t2);outcomes.add(Map.of("case","revoke-before-decision","task",t2,"result","pending; no manual effect"));
   // Expire a real grant while an already-authorized HTTP transaction is waiting on the target.
   long exp=930124;syntheticUser(exp,true);Instant expiry=Instant.now().plusSeconds(14).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);grantUntil(exp,"POST_SAFETY_REVIEW",expiry);String et=qr(exp);long ep=createPost("expiry during decision"),ert=task(ep);awaitState(ert,"pending");
   try(var lock=source(url).getConnection()) {lock.setAutoCommit(false);lock.createStatement().executeQuery("SELECT id FROM post WHERE id="+ep+" FOR UPDATE");var decide=executor.submit(()->call("POST","/v1/admin/reviews/"+ert+"/decisions",et,Map.of("action","approve"),key()));blocked("post");long millis=java.time.Duration.between(Instant.now(),expiry.plusMillis(500)).toMillis();if(millis>0)Thread.sleep(millis);lock.commit();assertTrue(Set.of(401,403).contains(decide.get(15,TimeUnit.SECONDS).statusCode()));
   }noDecision(ert);outcomes.add(Map.of("case","expiry-during-decision","task",ert,"result","pending; transaction rolled back"));
  } finally {executor.shutdownNow();}
  var dir=Path.of("target/safety-followup-evidence");Files.createDirectories(dir);JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("http.json").toFile(),wire);JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("outcomes.json").toFile(),outcomes);JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("facts.json").toFile(),Map.of("rounds",db.queryForList("SELECT * FROM content_safety_review"),"manual",db.queryForList("SELECT review_task_id,admin_user_id,admin_action FROM content_audit_log WHERE audit_source=2"),"comments",db.queryForList("SELECT id,target_type,target_id,status FROM comment")));
 }
}
