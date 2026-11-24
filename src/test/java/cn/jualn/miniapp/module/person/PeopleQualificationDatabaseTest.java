package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.roster.RosterUploadCommands;
import cn.jualn.miniapp.module.person.service.roster.RosterWorkbook;
import cn.jualn.miniapp.module.person.service.qualification.MentorManagementService;
import cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification;

import cn.jualn.miniapp.module.person.bo.QualificationBO.*;
import cn.jualn.miniapp.module.person.bo.PersonBO.*;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import cn.jualn.miniapp.module.post.service.MentorQualification;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL/proxies; reuses the explicit synthetic admin/session/approval fixture.
 * Run only #qualification* to avoid inheriting unrelated test methods. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="person.test.jdbcUrl",
        matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/person_foundation_test_[a-z0-9]+")
class PeopleQualificationDatabaseTest extends PersonFoundationDatabaseHttpTest {
    static final String STAGE="PARTY_ACTIVIST";
    static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Override @BeforeAll void start() throws Exception {
        System.setProperty("people-qualification.encryption-key",Base64.getEncoder().encodeToString(new byte[32]));
        var matching=new byte[32];Arrays.fill(matching,(byte)1);
        System.setProperty("people-qualification.match-key",Base64.getEncoder().encodeToString(matching));
        super.start();
    }
    @Override long user() { long id=sequence++; addUser(id); return id; }
    String plan(String id) { return roster().rows(1,id,1,20).value().planId(); }
    PartyRosterService roster() {return context.getBean(PartyRosterService.class);}
    MentorManagementService mentors() {return context.getBean(MentorManagementService.class);}
    PersistentMentorQualification qualification() {return context.getBean(PersistentMentorQualification.class);}
    Mapping mapping() {return new Mapping(0,1,new Columns(1,2,3,4),List.of(new StageValue("activist",STAGE)));}
    byte[] workbook(String name,String number) throws Exception {
        try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=w.createSheet("roster");var header=sheet.createRow(0);int i=0;
            for(var value:List.of("name","student","major class","stage")) header.createCell(i++).setCellValue(value);
            var row=sheet.createRow(1);i=0;for(var value:List.of(name,number,"major class","activist")) row.createCell(i++).setCellValue(value);
            w.write(out);return out.toByteArray();
        }
    }
    Resource<cn.jualn.miniapp.module.person.bo.QualificationBO.Import> baseline(String organization,String name,String number) throws Exception {
        catalog.confirmed=true;var upload=roster().upload(1,organization,"isolated approved source","test.xlsx",MIME,workbook(name,number),key());
        var mapped=roster().map(1,upload.value().importId(),mapping(),key(),upload.etag());
        return roster().commit(1,mapped.value().importId(),new Commit("approved source","synthetic evidence",true,plan(mapped.value().importId())),key(),mapped.etag());
    }
    @Test void qualificationImportClaimPreservesUnknownMembershipAndAuditsSource() throws Exception {
        String org=org(),number="000"+key();long user=user();long users=count("user_profile");
        var baseline=baseline(org,"student A",number);assertEquals(users,count("user_profile"));
        String createdPerson=db.queryForObject("SELECT person_id FROM party_roster_entry WHERE import_id=?",String.class,baseline.value().importId());
        assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM person_membership WHERE person_id=? AND organization_id=? AND ended_event_id IS NULL",Long.class,createdPerson,org));
        assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM person_local_membership WHERE person_id=? AND organization_id=? AND presence='PRESENT' AND membership_id IS NOT NULL",Long.class,createdPerson,org));
        var incomplete=service.memberships(1,createdPerson).value();
        assertEquals(cn.jualn.miniapp.module.person.bo.PersonBO.Verification.UNKNOWN,incomplete.verification());
        assertNull(incomplete.currentMemberships());
        assertEquals(List.of(org),incomplete.knownMemberships().stream().map(cn.jualn.miniapp.module.person.bo.PersonBO.Member::organizationId).toList());
        assertEquals("REGISTERED_CURRENT_MEMBERS",incomplete.knownMembershipsCoverage());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM party_identity_application WHERE applicant=?",Integer.class,user));var command=new Apply("student A",number);String requestKey=key();
        var claimed=roster().apply(user,command,requestKey);assertEquals("APPROVED",claimed.value().status());
        assertEquals(claimed,roster().apply(user,command,requestKey));problem("roster-state-conflict",()->roster().apply(user,command,key()));
        assertEquals("VERIFIED",db.queryForObject("SELECT identity_verification FROM person_fact_current WHERE user_id=?",String.class,user));
        assertEquals("UNKNOWN",db.queryForObject("SELECT membership_verification FROM person_fact_current WHERE user_id=?",String.class,user));
        assertEquals(1L,service.organizationMembers(1,org,1,20).totalItems());
        var readerEvidence=tx.execute(s->{assertEquals(0,mapper.corruptSource());assertEquals(0,mapper.corruptFacts());var c=readers.open(user);var identity=readers.identity(user,c);assertEquals("ROSTER_EXACT_MATCH",identity.source());return json.valueToTree(identity);});
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/people-qualification"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/people-qualification/reader.json").toFile(),readerEvidence);
        assertEquals(1,roster().applications(user,1,20).items().size());
        var list=roster().entries(1,1,20,new EntryQuery(org,null,null,null,null,null));assertNotNull(list.items().get(0).personId());
        String entry=list.items().get(0).rosterEntryId();assertEquals(2,roster().history(1,entry,1,20).items().size());
        assertFalse(json.writeValueAsString(roster().getImport(1,baseline.value().importId())).contains(number));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_foundation_event WHERE CAST(after_json AS CHAR) LIKE ? OR CAST(before_json AS CHAR) LIKE ?",Integer.class,"%"+number+"%","%"+number+"%"));
        tx.executeWithoutResult(s->{var c=readers.open(user);var owner=context.getBean(PersonBusinessScopes.class);
            assertEquals(STAGE,owner.developmentStage(user,c.evaluatedAt()));assertThrows(ContractProblemException.class,()->owner.requireRoster(org,c.evaluatedAt()));s.setRollbackOnly();});
        var points=new cn.jualn.miniapp.module.points.service.CurrentPointsDependencies(
            Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class),context.getBean(cn.jualn.miniapp.module.authorization.service.RbacCatalog.class),
            context.getBean(cn.jualn.miniapp.module.user.service.UserAccountService.class),context.getBean(cn.jualn.miniapp.module.admin.auth.service.AdminTokenService.class),readers,catalog,
            context.getBean(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class),context.getBean(org.springframework.jdbc.datasource.DataSourceTransactionManager.class),
            Mockito.mock(cn.jualn.miniapp.module.points.service.PointsResultNotifications.class),Mockito.mock(cn.jualn.miniapp.module.points.mapper.PointsMapper.class),
            Mockito.mock(cn.jualn.miniapp.module.points.service.PointsPrivateMaterials.class),Mockito.mock(cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess.class));
        try(var stp=Mockito.mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            stp.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsLong).thenReturn(user);
            tx.executeWithoutResult(s->{points.begin(cn.jualn.miniapp.module.points.bo.PointsBO.Action.ELIGIBILITY,user);assertTrue(points.approvalEligiblePerson(subject(user)));});
        }
    }
    @Test void qualificationConcurrentClaimAndRollbackHaveNoPartialFacts() throws Exception {
        String org=org(),number="000"+key();baseline(org,"student B",number);long a=user(),b=user();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var work=new ArrayList<Future<Boolean>>();for(long u:new long[]{a,b}) work.add(pool.submit(()->{start.await();try {roster().apply(u,new Apply("student B",number),key());return true;} catch(ContractProblemException e) {assertEquals("/problems/roster-state-conflict",e.getType());return false;}}));
            start.countDown();int wins=0;for(var f:work) if(f.get(20,TimeUnit.SECONDS)) wins++;assertEquals(1,wins);
        } finally {pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM party_identity_application WHERE applicant IN (?,?)",Integer.class,a,b));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM person_fact_current WHERE user_id IN (?,?)",Integer.class,a,b));
        String n2="000"+key();baseline(org,"student C",n2);long target=user();long events=count("person_foundation_event"),receipts=count("person_command_receipt");
        tx.executeWithoutResult(s->{roster().apply(target,new Apply("student C",n2),key());s.setRollbackOnly();});
        assertEquals(events,count("person_foundation_event"));assertEquals(receipts,count("person_command_receipt"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_fact_current WHERE user_id=?",Integer.class,target));
        assertEquals("APPROVED",roster().apply(target,new Apply("student C",n2),key()).value().status());
    }
    @Test void qualificationRejectsConflictingIdentityDuplicateWorkbookAndChangedKey() throws Exception {
        String org=org(),number="000"+key();var bytes=workbook("student D",number);catalog.confirmed=true;String uploadKey=key();
        var upload=roster().upload(1,org,"source","t.xlsx",MIME,bytes,uploadKey);
        assertEquals(upload,roster().upload(1,org,"source","t.xlsx",MIME,bytes,uploadKey));
        problem("people-idempotency-conflict",()->roster().upload(1,org,"different","t.xlsx",MIME,bytes,uploadKey));
        var mapped=roster().map(1,upload.value().importId(),mapping(),key(),upload.etag());roster().commit(1,upload.value().importId(),new Commit("reason","ref",true,plan(upload.value().importId())),key(),mapped.etag());
        long target=user();var identity=service.identity(1,subject(target));service.changeIdentity(1,subject(target),new IdentityChange(ChangeKind.VERIFY_NONE,null,null,null,"explicit none","evidence"),key(),identity.etag());
        long before=count("person_foundation_event");problem("roster-state-conflict",()->roster().apply(target,new Apply("student D",number),key()));assertEquals(before,count("person_foundation_event"));
        var duplicate=roster().upload(1,org,"duplicate","t.xlsx",MIME,bytes,key());var preview=roster().map(1,duplicate.value().importId(),mapping(),key(),duplicate.etag());assertEquals(1,preview.value().invalidRowCount());
        problem("roster-import-invalid",()->roster().commit(1,duplicate.value().importId(),new Commit("reason","ref",true,plan(duplicate.value().importId())),key(),preview.etag()));
    }
    void enableMentors() {
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='mentor:qualification-manage'");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='MENTOR_REGISTRAR'");
    }
    @Test void qualificationMentorApprovalRevocationExpiryAndAccountAreOneReader() throws Exception {
        enableMentors();long mentor=user();var profile=mentors().put(1,subject(mentor),new ProfileWrite("position","unit","verified","ref"),key(),null,"*");
        assertEquals("NOT_GRANTED",profile.value().qualificationStatus());assertEquals(MentorQualification.State.INELIGIBLE,qualification().evaluate(mentor).state());
        String approvalKey=key();var command=new QualificationChange("APPROVE",Instant.now().plusSeconds(180).truncatedTo(java.time.temporal.ChronoUnit.MICROS),"approved","trusted evidence");
        var approved=mentors().change(1,subject(mentor),command,approvalKey,profile.etag());assertEquals("ACTIVE",approved.value().qualificationStatus());
        assertEquals(approved,mentors().change(1,subject(mentor),command,approvalKey,profile.etag()));assertTrue(qualification().evaluate(mentor).valid());assertTrue(mentors().certified(9,mentor).certified());
        var revoked=mentors().change(1,subject(mentor),new QualificationChange("REVOKE",null,"withdraw","evidence"),key(),approved.etag());
        assertEquals("REVOKED",revoked.value().qualificationStatus());assertEquals(MentorQualification.State.INELIGIBLE,qualification().evaluate(mentor).state());
        problem("not-found",()->mentors().certified(9,mentor));assertEquals(3,mentors().history(1,subject(mentor),1,20).totalItems());
        var expires=mentors().change(1,subject(mentor),new QualificationChange("APPROVE",Instant.now().plusMillis(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS),"short period","ref"),key(),revoked.etag());
        Thread.sleep(550);assertEquals("EXPIRED",mentors().get(1,subject(mentor)).value().qualificationStatus());assertFalse(qualification().evaluate(mentor).valid());
        var active=mentors().change(1,subject(mentor),new QualificationChange("APPROVE",null,"approve","ref"),key(),expires.etag());
        db.update("UPDATE user_profile SET status=3 WHERE id=?",mentor);assertEquals("ACCOUNT_UNAVAILABLE",mentors().get(1,subject(mentor)).value().qualificationStatus());assertFalse(qualification().evaluate(mentor).valid());
        db.update("UPDATE user_profile SET status=1 WHERE id=?",mentor);assertTrue(qualification().evaluate(mentor).valid());
        assertEquals(0L,tx.execute(s->mapper.corruptSource()).longValue());
    }
    @Test void qualificationHttpAdminResourcesAndMiniMvcUseCanonicalRepresentations() throws Exception {
        enableMentors();catalog.confirmed=true;String organization=org(),number="000"+key();
        String boundary="pqBoundary";var bytes=workbook("http student",number);var raw=new ByteArrayOutputStream();
        raw.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"organizationId\"\r\n\r\n"+organization+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"sourceReference\"\r\n\r\napproved source\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.xlsx\"\r\nContent-Type: "+MIME+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        raw.write(bytes);raw.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String uploadKey=key();var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/v1/admin/party-roster-imports")).header("Content-Type","multipart/form-data; boundary="+boundary)
            .header("X-Person-Test-Actor","1").header("Idempotency-Key",uploadKey).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(raw.toByteArray())).build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(201,response.statusCode(),response.body());
        record("POST","/v1/admin/party-roster-imports",201,"PartyRosterImport",response.body(),response.headers().firstValue("ETag").orElse(null),response.headers().firstValue("Location").orElse(null),response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());String id=json.readTree(response.body()).get("importId").asText();
        var uploaded=http("GET","/v1/admin/party-roster-imports/"+id,1,null,null,null,200,"PartyRosterImport");
        http("GET","/v1/admin/party-roster-imports/"+id+"/sheets",1,null,null,null,200,"PartyRosterWorkbookSheets");
        http("GET","/v1/admin/party-roster-imports/"+id+"/columns?sheetIndex=0&headerRow=1",1,null,null,null,200,"PartyRosterHeaderColumns");
        var mappingRequest = json.valueToTree(mapping());
        ((com.fasterxml.jackson.databind.node.ObjectNode) mappingRequest.path("columns")).remove("majorClass");
        String mappingBody = json.writeValueAsString(mappingRequest);
        String mappingKey = key();
        var mapped=http("PUT","/v1/admin/party-roster-imports/"+id+"/mapping",1,mappingBody,mappingKey,uploaded.etag(),200,"PartyRosterImport");
        String planId = mapped.body().path("planId").asText();
        assertFalse(planId.isBlank());
        assertTrue(Instant.parse(mapped.body().path("planExpiresAt").asText()).isAfter(Instant.now()));
        assertFalse(mapped.body().path("mapping").path("columns").has("majorClass"));
        var mappedAgain=http("PUT","/v1/admin/party-roster-imports/"+id+"/mapping",1,mappingBody,mappingKey,uploaded.etag(),200,"PartyRosterImport");
        assertEquals(planId,mappedAgain.body().path("planId").asText());
        var currentImport=http("GET","/v1/admin/party-roster-imports/"+id,1,null,null,null,200,"PartyRosterImport");
        assertEquals(planId,currentImport.body().path("planId").asText());
        var previewImports=http("GET","/v1/admin/party-roster-imports",1,null,null,null,200,"PartyRosterImportPage");
        var previewImport=java.util.stream.StreamSupport.stream(previewImports.body().path("items").spliterator(),false)
            .filter(item->id.equals(item.path("importId").asText())).findFirst().orElseThrow();
        assertEquals(planId,previewImport.path("planId").asText());
        assertEquals("CAMPUS_STUDENT_NUMBER",previewImport.path("namespace").asText());
        assertEquals("AVAILABLE",previewImport.path("fileStatus").asText());
        var preview=http("GET","/v1/admin/party-roster-imports/"+id+"/rows",1,null,null,null,200,"PartyRosterPreviewRowPage");
        assertEquals(planId,preview.body().path("planId").asText());
        assertEquals(number,preview.body().path("items").get(0).path("studentNumber").asText());assertEquals(mapped.etag(),preview.etag());
        assertEquals(id,preview.body().path("importId").asText());assertEquals(0,preview.body().path("sheetIndex").asInt());
        http("POST","/v1/admin/party-roster-imports/"+id+":commit",1,json.writeValueAsString(new Commit("reason","evidence",true,plan(id))),key(),mapped.etag(),200,"PartyRosterImport");
        http("GET","/v1/admin/party-roster-imports?state=COMMITTED",1,null,null,null,200,"PartyRosterImportPage");
        var recovered=http("GET","/v1/admin/party-roster-imports/creation-receipt?organizationId="+organization,1,null,uploadKey,null,200,"PartyRosterImportCreationRecovery");assertEquals(id,recovered.body().get("importId").asText());
        var entries=http("GET","/v1/admin/party-roster-entries?organizationId="+organization,1,null,null,null,200,"PartyRosterEntryPage");
        assertTrue(entries.body().get("totalItems").isIntegralNumber());String entry=entries.body().get("items").get(0).get("rosterEntryId").asText();
        var detail=http("GET","/v1/admin/party-roster-entries/"+entry,1,null,null,null,200,"PartyRosterEntry");
        http("PUT","/v1/admin/party-roster-entries/"+entry,1,json.writeValueAsString(new Correction("http student",number,"major class",STAGE,"ACTIVE","verified","ref")),key(),detail.etag(),200,"PartyRosterEntry");
        http("GET","/v1/admin/party-roster-entries/"+entry+"/history",1,null,null,null,200,"PeopleQualificationAuditPage");
        auth.denied.add("party-personnel:manage");http("GET","/v1/admin/party-roster-entries",1,null,null,null,403,"ProblemDetails");auth.denied.clear();
        long applicant=user();var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(context.getBean(cn.jualn.miniapp.module.person.controller.QualificationController.class))
            .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(),new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)))
            .setControllerAdvice(context.getBean(cn.jualn.miniapp.module.person.controller.QualificationProblemAdvice.class)).build();
        try(var stp=Mockito.mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            stp.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsLong).thenReturn(applicant);
            var unknown=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/party-identity")).andReturn().getResponse();assertEquals(200,unknown.getStatus());assertEquals("UNKNOWN",json.readTree(unknown.getContentAsString()).get("effectiveStatus").asText());
            record("GET","/v1/users/me/party-identity",200,"MyPartyIdentity",unknown.getContentAsString(),null,null,unknown.getHeader("Cache-Control"));
            var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/users/me/party-identity-applications").contentType("application/json").header("Idempotency-Key",key()).content(json.writeValueAsBytes(new Apply("http student",number)))).andReturn().getResponse();
            assertEquals(201,result.getStatus(),result.getContentAsString());assertEquals("ROSTER_EXACT_MATCH",json.readTree(result.getContentAsString()).get("verificationMethod").asText());
            record("POST","/v1/users/me/party-identity-applications",201,"PartyIdentityApplication",result.getContentAsString(),result.getHeader("ETag"),result.getHeader("Location"),result.getHeader("Cache-Control"));
            var mine=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/party-identity-applications")).andReturn().getResponse();assertEquals(200,mine.getStatus());
            record("GET","/v1/users/me/party-identity-applications",200,"PartyIdentityApplicationPage",mine.getContentAsString(),null,null,mine.getHeader("Cache-Control"));
            var current=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/users/me/party-identity")).andReturn().getResponse();assertEquals(200,current.getStatus());assertEquals("ACTIVE",json.readTree(current.getContentAsString()).get("effectiveStatus").asText());
            record("GET","/v1/users/me/party-identity",200,"MyPartyIdentity",current.getContentAsString(),null,null,current.getHeader("Cache-Control"));
        }
        var linked=http("GET","/v1/admin/party-roster-entries/"+entry,1,null,null,null,200,"PartyRosterEntry");
        assertTrue(linked.body().get("personIdentity").get("verifiedAt").asText().endsWith("Z"));
        var list=http("GET","/v1/admin/mentors",1,null,null,null,200,"MentorAdminProfilePage");assertTrue(list.body().get("totalItems").isIntegralNumber());
        long mentor=user();String path="/v1/admin/mentors/"+mentor;
        var create=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path)).header("Content-Type","application/json").header("X-Person-Test-Actor","1").header("Idempotency-Key",key()).header("If-None-Match","*")
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new ProfileWrite("position","unit","verified","evidence")))).build();
        var created=java.net.http.HttpClient.newHttpClient().send(create,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(201,created.statusCode(),created.body());
        record("PUT",path,201,"MentorAdminProfile",created.body(),created.headers().firstValue("ETag").orElse(null),created.headers().firstValue("Location").orElse(null),created.headers().firstValue("Cache-Control").orElse(null));
        var profile=http("GET",path,1,null,null,null,200,"MentorAdminProfile");
        var updated=http("PUT",path,1,json.writeValueAsString(new ProfileWrite("new position","unit","verified","evidence")),key(),profile.etag(),200,"MentorAdminProfile");
        http("PUT",path,1,json.writeValueAsString(new ProfileWrite("position","unit","verified","evidence")),key(),null,428,"ProblemDetails");
        http("PUT",path,1,json.writeValueAsString(new ProfileWrite("position","unit","verified","evidence")),key(),profile.etag(),412,"ProblemDetails");
        var approved=http("POST",path+"/qualification-changes",1,json.writeValueAsString(new QualificationChange("APPROVE",null,"approved","evidence")),key(),updated.etag(),200,"MentorAdminProfile");
        http("GET",path+"/history",1,null,null,null,200,"PeopleQualificationAuditPage");
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='content:read'");
        http("GET",path+"/answers",1,null,null,null,200,"MentorAnswerReferencePage");
        try(var stp=Mockito.mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            stp.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsLong).thenReturn(applicant);
            var certified=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/mentors/"+mentor)).andReturn().getResponse();assertEquals(200,certified.getStatus(),certified.getContentAsString());
            record("GET","/v1/mentors/"+mentor,200,"CertifiedMentorProfile",certified.getContentAsString(),null,null,certified.getHeader("Cache-Control"));
        }
        http("POST",path+"/qualification-changes",1,json.writeValueAsString(new QualificationChange("REVOKE",null,"withdraw","evidence")),key(),approved.etag(),200,"MentorAdminProfile");
        http("PUT",path,1,"{\"realName\":\"name\",\"verified\":true}",key(),approved.etag(),400,"ProblemDetails");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/people-qualification"));json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/people-qualification/http.json").toFile(),httpEvidence);
    }
    void record(String method,String path,int status,String schema,String body,String etag,String location,String cache) throws Exception {
        var evidence=new LinkedHashMap<String,Object>();evidence.put("method",method);evidence.put("path",path);evidence.put("status",status);evidence.put("schema",schema);evidence.put("body",json.readTree(body));
        evidence.put("etag",etag);evidence.put("location",location);evidence.put("cacheControl",cache);evidence.put("contentType",status<400?"application/json":"application/problem+json");httpEvidence.add(evidence);
    }
    @Test void qualificationAnswerAndRevocationSerializeAndHistoryUsesCommentAuthor() throws Exception {
        enableMentors();long mentor=user(),author=user();var profile=mentors().put(1,subject(mentor),new ProfileWrite("position","unit","verified","ref"),key(),null,"*");
        var approved=mentors().change(1,subject(mentor),new QualificationChange("APPROVE",null,"approved","ref"),key(),profile.etag());
        long post=sequence++;long category=db.queryForObject("SELECT MIN(id) FROM post_category",Long.class);
        db.update("INSERT INTO post(id,user_id,content,status,audit_status,post_type,category_id) VALUES(?,?,'question',2,1,'MENTOR_QA',?)",post,author,category);
        var policy=new cn.jualn.miniapp.module.comment.service.PostQaCommentPolicy(new cn.jualn.miniapp.module.post.service.PostCommentPermissions(qualification()));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var answer=pool.submit(()->tx.execute(s->{String proof=policy.authorize(new cn.jualn.miniapp.module.post.bo.PostCommentAccessBO(author,"MENTOR_QA"),mentor,null,null);
                entered.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));} catch(InterruptedException e) {throw new IllegalStateException(e);}
                return db.update("INSERT INTO comment(target_type,target_id,user_id,content,status,audit_status,mentor_evidence) VALUES(1,?,?,'formal answer',1,1,?)",post,mentor,proof);}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));var revoke=pool.submit(()->mentors().change(1,subject(mentor),new QualificationChange("REVOKE",null,"withdraw","ref"),key(),approved.etag()));
            Thread.sleep(100);assertFalse(revoke.isDone());release.countDown();assertEquals(1,answer.get(10,TimeUnit.SECONDS));assertEquals("REVOKED",revoke.get(10,TimeUnit.SECONDS).value().qualificationStatus());
        } finally {release.countDown();pool.shutdownNow();}
        assertThrows(ContractProblemException.class,()->tx.execute(s->policy.authorize(new cn.jualn.miniapp.module.post.bo.PostCommentAccessBO(author,"MENTOR_QA"),mentor,null,null)));
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='content:read'");
        var answers=mentors().answers(1,subject(mentor),1,20);assertEquals(1,answers.totalItems());assertEquals(Long.toString(post),answers.items().get(0).postId());
        assertEquals(0,context.getBean(QualificationMapper.class).answerCount(subject(author)));
    }
    @Test void qualificationPreservesExistingIdentityCycleAndSuspensionDoesNotRewritePerson() throws Exception {
        String organization=org(),number="000"+key();baseline(organization,"existing student",number);long user=user();
        var before=service.identity(1,subject(user));var identity=service.changeIdentity(1,subject(user),new IdentityChange(ChangeKind.RECORD,STAGE,LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0),null,"verified","ref"),key(),before.etag());
        var local=service.changeLocalMembership(1,subject(user),organization,new LocalMemberChange(LocalMemberKind.JOIN,"verified","ref"),key(),service.localMembership(1,subject(user),organization).etag());
        roster().apply(user,new Apply("existing student",number),key());
        assertEquals(identity.value().currentRecord(),service.identity(1,subject(user)).value().currentRecord());assertEquals(local.value().membershipId(),service.localMembership(1,subject(user),organization).value().membershipId());
        var entry=roster().entries(1,1,20,new EntryQuery(organization,null,null,null,null,null)).items().get(0);
        problem("roster-state-conflict",()->roster().correct(1,entry.rosterEntryId(),new Correction("different name",number,"major class",STAGE,"SUSPENDED","correct","ref"),key(),"\""+entry.version()+"\""));
        roster().correct(1,entry.rosterEntryId(),new Correction("existing student",number,"major class",STAGE,"SUSPENDED","stop source","ref"),key(),"\""+entry.version()+"\"");
        assertEquals(identity.value().currentRecord(),service.identity(1,subject(user)).value().currentRecord());assertEquals(1,service.organizationMembers(1,organization,1,20).totalItems());
        String n2="000"+key();baseline(organization,"suspended student",n2);var source=roster().entries(1,1,20,new EntryQuery(organization,"UNLINKED",null,null,null,null)).items().get(0);long applicant=user();
        roster().correct(1,source.rosterEntryId(),new Correction("suspended student",n2,"major class",STAGE,"SUSPENDED","stop source","ref"),key(),"\""+source.version()+"\"");
        problem("roster-state-conflict",()->roster().apply(applicant,new Apply("suspended student",n2),key()));
    }
    @Test void qualificationExpiryBeforeCommitRollsBackFormalAnswerAndUnknownSourceBlocks() throws Exception {
        enableMentors();long mentor=user();var profile=mentors().put(1,subject(mentor),new ProfileWrite("position","unit","verified","ref"),key(),null,"*");
        mentors().change(1,subject(mentor),new QualificationChange("APPROVE",Instant.now().plusMillis(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS),"approved","ref"),key(),profile.etag());
        long comments=count("comment");var permissions=new cn.jualn.miniapp.module.post.service.PostCommentPermissions(qualification());
        assertThrows(ContractProblemException.class,()->tx.execute(s->{String proof=permissions.requireMentor(mentor);
            db.update("INSERT INTO comment(target_type,target_id,user_id,content,mentor_evidence) VALUES(1,1,?,'must roll back',?)",mentor,proof);
            try {Thread.sleep(1000);} catch(InterruptedException e) {throw new IllegalStateException(e);}return null;}));
        assertEquals(comments,count("comment"));
        tx.executeWithoutResult(s->{db.update("UPDATE person_command_receipt SET fingerprint='broken' WHERE event_id=(SELECT last_event_id FROM mentor_profile WHERE user_id=?)",mentor);
            assertEquals(MentorQualification.State.UNAVAILABLE,qualification().evaluate(mentor).state());s.setRollbackOnly();});
        assertEquals(MentorQualification.State.INELIGIBLE,qualification().evaluate(mentor).state());
    }
    @Test void qualificationCreationRecoveryIsDurableAndScopedToActorOrganizationAndKey() throws Exception {
        String organization=org(),requestKey=key();catalog.confirmed=true;var bytes=workbook("recover student","000"+key());
        assertEquals("NOT_FOUND",roster().creationReceipt(1,organization,requestKey).state());long batches=count("party_roster_import"),events=count("person_foundation_event");
        var commands=context.getBean(RosterUploadCommands.class);var reservation=commands.reserve(1,organization,"source",bytes,requestKey);
        assertEquals("IN_PROGRESS",roster().creationReceipt(1,organization,requestKey).state());assertEquals(batches,count("party_roster_import"));assertEquals(events,count("person_foundation_event"));
        problem("people-operation-in-progress",()->roster().upload(1,organization,"source","t.xlsx",MIME,bytes,requestKey));
        var complete=commands.complete(1,reservation.command(),bytes,context.getBean(RosterWorkbook.class).columns(bytes),requestKey);
        assertEquals(complete.value().importId(),roster().creationReceipt(1,organization,requestKey).importId());
        assertEquals(complete,roster().upload(1,organization,"source","t.xlsx",MIME,bytes,requestKey));
        var prior=roster().imports(1,1,100,null);assertTrue(prior.items().stream().anyMatch(i->i.importId().equals(complete.value().importId())));
        assertEquals(0,roster().imports(2,1,100,null).totalItems());
        orgGrant(2,organization,Domain.IDENTITY,Action.READ);orgGrant(2,organization,Domain.ORGANIZATION,Action.READ);
        assertEquals("NOT_FOUND",roster().creationReceipt(2,organization,requestKey).state());
        String pendingKey=key();commands.reserve(1,organization,"recover interrupted",bytes,pendingKey);
        db.update("UPDATE person_command_receipt SET created_at=TIMESTAMPADD(MINUTE,-3,created_at) WHERE actor=1 AND operation='createPartyRosterImport' AND command_key=?",pendingKey);
        var recovered=roster().upload(1,organization,"recover interrupted","t.xlsx",MIME,bytes,pendingKey);assertEquals("SUCCEEDED",roster().creationReceipt(1,organization,pendingKey).state());assertNotNull(recovered.value().importId());
    }
    @Test void qualificationOwnIdentityUsesCurrentOriginalSourceAndHidesUnknownHistory() throws Exception {
        String organization=org(),number="000"+key();baseline(organization,"manual student",number);long person=user();
        assertEquals("UNKNOWN",roster().myIdentity(person).effectiveStatus());
        service.changeIdentity(1,subject(person),new IdentityChange(ChangeKind.RECORD,STAGE,LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0),null,"verified","ref"),key(),service.identity(1,subject(person)).etag());
        roster().apply(person,new Apply("manual student",number),key());assertEquals("ADMIN_MANUAL",roster().myIdentity(person).verificationMethod());
        var identity=service.identity(1,subject(person));service.changeIdentity(1,subject(person),new IdentityChange(ChangeKind.REVOKE,null,null,null,"withdraw","ref"),key(),identity.etag());assertEquals("REVOKED",roster().myIdentity(person).effectiveStatus());
        service.changeIdentity(1,subject(person),new IdentityChange(ChangeKind.MARK_UNKNOWN,null,null,null,"unknown","ref"),key(),service.identity(1,subject(person)).etag());
        assertNull(roster().myIdentity(person).currentRecord());assertNull(roster().myIdentity(person).verificationMethod());
        service.changeIdentity(1,subject(person),new IdentityChange(ChangeKind.VERIFY_NONE,null,null,null,"none","ref"),key(),service.identity(1,subject(person)).etag());assertEquals("NONE",roster().myIdentity(person).effectiveStatus());
        service.changeIdentity(1,subject(person),new IdentityChange(ChangeKind.MARK_DISPUTED,null,null,null,"disputed","ref"),key(),service.identity(1,subject(person)).etag());assertEquals("DISPUTED",roster().myIdentity(person).effectiveStatus());
    }
    @Test void qualificationWorkbookReadsAreOwnedVersionStableAndBoundToPreview() throws Exception {
        String organization=org();var bytes=workbook("verify student"," 000987 ");catalog.confirmed=true;
        var upload=roster().upload(1,organization,"source","t.xlsx",MIME,bytes,key());String id=upload.value().importId();
        assertEquals(upload.etag(),roster().sheets(1,id).etag());assertEquals(4,roster().columns(1,id,0,1).value().columns().size());
        problem("not-found",()->roster().sheets(2,id));problem("validation-error",()->roster().columns(1,id,2,1));
        var mapped=roster().map(1,id,mapping(),key(),upload.etag());var preview=roster().rows(1,id,1,20);
        assertEquals(" 000987 ",preview.value().items().get(0).studentNumber());assertEquals(mapped.etag(),preview.etag());
        long events=count("person_foundation_event");roster().sheets(1,id);roster().columns(1,id,0,2);assertEquals(events,count("person_foundation_event"));
        var remapped=roster().map(1,id,mapping(),key(),mapped.etag());
        assertThrows(ContractProblemException.class,()->roster().commit(1,id,new Commit("confirm","ref",true,plan(id)),key(),preview.etag()));
        roster().commit(1,id,new Commit("confirm","ref",true,plan(id)),key(),remapped.etag());
        long user=user();roster().apply(user,new Apply("verify student","000987"),key());
        assertEquals("ACTIVE",roster().myIdentity(user).effectiveStatus());
        var entry=roster().entries(1,1,20,new EntryQuery(organization,null,null,null,null,null)).items().get(0);
        roster().correct(1,entry.rosterEntryId(),new Correction("verify student"," 000987 ","updated class",STAGE,"ACTIVE","correct class","ref"),key(),"\""+entry.version()+"\"");
    }
    @Test void qualificationWorkbookReadsExpireAndFailClosedAndOldPreviewMustBeRemapped() throws Exception {
        String organization=org();catalog.confirmed=true;var upload=roster().upload(1,organization,"source","t.xlsx",MIME,workbook("legacy","000"+key()),key());String id=upload.value().importId();
        var mapped=roster().map(1,id,mapping(),key(),upload.etag());
        String old="{\"sheetIndex\":0,\"headerRow\":1,\"columns\":{\"realName\":1,\"studentNumber\":2,\"major\":3,\"className\":4,\"stageId\":5},\"stageValues\":[{\"cellValue\":\"activist\",\"stageId\":\"PARTY_ACTIVIST\"}]}";
        db.update("UPDATE party_roster_import SET mapping_json=? WHERE id=?",old,id);
        problem("roster-state-conflict",()->roster().rows(1,id,1,20));problem("roster-state-conflict",()->roster().commit(1,id,new Commit("confirm","ref",true,plan(id)),key(),mapped.etag()));
        var remapped=roster().map(1,id,mapping(),key(),mapped.etag());assertEquals(1,roster().rows(1,id,1,20).value().totalItems());
        db.update("UPDATE party_roster_import SET expires_at=TIMESTAMPADD(DAY,-1,expires_at) WHERE id=?",id);
        problem("roster-import-expired",()->roster().sheets(1,id));problem("roster-import-expired",()->roster().columns(1,id,0,1));
        db.update("UPDATE party_roster_import SET expires_at=TIMESTAMPADD(DAY,2,expires_at),workbook=X'01' WHERE id=?",id);
        problem("people-source-unavailable",()->roster().sheets(1,id));
    }
    @Test void qualificationMentorPublicationOpensManagementWithoutGrantingBusinessQualification() throws Exception {
        assertEquals("READY",db.queryForObject("SELECT readiness FROM rbac_permission_definition WHERE code='mentor:qualification-manage'",String.class));
        assertEquals("READY",db.queryForObject("SELECT readiness FROM rbac_role WHERE code='MENTOR_REGISTRAR'",String.class));
        long mentor=user();assertEquals(MentorQualification.State.INELIGIBLE,qualification().evaluate(mentor).state());
        var profile=mentors().put(1,subject(mentor),new ProfileWrite("position","unit","verified","ref"),key(),null,"*");
        mentors().change(1,subject(mentor),new QualificationChange("APPROVE",null,"approve","ref"),key(),profile.etag());assertTrue(qualification().evaluate(mentor).valid());
        tx.executeWithoutResult(status->{db.update("UPDATE rbac_permission_definition SET lifecycle='DISABLED',grantable=0 WHERE code='mentor:qualification-manage'");problem("people-source-unavailable",()->mentors().get(1,subject(mentor)));status.setRollbackOnly();});
    }

    @Test void qualificationNumericStudentImportsClaimsAndFormulaPreviewCannotCommit() throws Exception {
        String organization=org();catalog.confirmed=true;byte[] numeric,formula;
        try(var w=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook("numeric student","unused")));var out=new ByteArrayOutputStream()) {
            var cell=w.getSheetAt(0).getRow(1).getCell(1);cell.setCellValue(2026123456L);w.write(out);numeric=out.toByteArray();
            out.reset();cell.setCellFormula("2026123456");w.write(out);formula=out.toByteArray();
        }
        var upload=roster().upload(1,organization,"numeric source","n.xlsx",MIME,numeric,key());String id=upload.value().importId();
        var mapped=roster().map(1,id,mapping(),key(),upload.etag());assertEquals("2026123456",roster().rows(1,id,1,20).value().items().get(0).studentNumber());
        roster().commit(1,id,new Commit("verified","ref",true,plan(id)),key(),mapped.etag());long claimant=user();
        roster().apply(claimant,new Apply("numeric student","2026123456"),key());assertEquals("ROSTER_EXACT_MATCH",roster().myIdentity(claimant).verificationMethod());
        var bad=roster().upload(1,organization,"formula source","f.xlsx",MIME,formula,key());
        var badMap=roster().map(1,bad.value().importId(),mapping(),key(),bad.etag());assertEquals(1,badMap.value().invalidRowCount());
        var preview=roster().rows(1,bad.value().importId(),1,20).value().items().get(0);
        assertEquals("=2026123456",preview.studentNumber());assertEquals("FORMULA_CELL",preview.errors().get(0).reason());
        long entries=count("party_roster_entry"),events=count("person_foundation_event");
        problem("roster-import-invalid",()->roster().commit(1,bad.value().importId(),new Commit("verified","ref",true,plan(bad.value().importId())),key(),badMap.etag()));
        assertEquals(entries,count("party_roster_entry"));assertEquals(events,count("person_foundation_event"));
    }

}
