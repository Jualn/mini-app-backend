package cn.jualn.miniapp.module.person;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.profile.PersonIdentifierNamespaces;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTransitions;
import cn.jualn.miniapp.module.person.service.roster.PartyRosterService;
import cn.jualn.miniapp.module.person.service.qualification.MentorManagementService;
import cn.jualn.miniapp.module.person.service.support.ManagedPersonJournal;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.*;
import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.controller.ManagedPersonController;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owned new schema on the explicitly identified local development MySQL instance.
 * Real Flyway/MyBatis/transactions/HTTP; admin identity/capability adapter is synthetic. */
@EnabledIfSystemProperty(named="managed.person.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/independent_person_test_[a-z0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedPersonDatabaseHttpTest {
    static final AtomicBoolean loseCommit = new AtomicBoolean();
    ConfigurableApplicationContext context;
    JdbcTemplate db;
    ManagedPersonService people;
    ManagedPersonIdentifierService identifiers;
    ManagedPersonBindingService bindings;
    PersonFoundationDatabaseHttpTest.TestAuthentication auth;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final List<Object> evidence = new ArrayList<>();
    final List<Object> rosterProjections = new ArrayList<>();
    String base;
    long userSequence = 100;

    static DataSource source(String url) {
        String user = Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_USER"));
        String password = Objects.requireNonNull(System.getenv("MANAGED_PERSON_TEST_DB_PASSWORD"));
        return new DriverManagerDataSource(url + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true", user, password);
    }
    @BeforeAll void start() throws Exception {
        String url = System.getProperty("managed.person.test.jdbcUrl");
        String schema = url.substring(url.lastIndexOf('/') + 1);
        var admin = new JdbcTemplate(source(url.substring(0, url.lastIndexOf('/')) + "/mysql"));
        assertEquals(System.getProperty("managed.person.test.serverUuid"), admin.queryForObject("SELECT @@server_uuid", String.class));
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        // schema has a strict lowercase alphanumeric allowlist from EnabledIfSystemProperty.
        admin.execute("CREATE DATABASE " + schema);
        var flyway = Flyway.configure().dataSource(source(url)).load(); flyway.migrate(); flyway.validate();
        context = SpringApplication.run(Config.class, "--spring.profiles.active=dev", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.config.location=optional:classpath:/managed-person-no-config.yaml", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--people-qualification.encryption-key=" + testKey(), "--people-qualification.match-key=" + testKey(),
                "--people-qualification.student-number-namespace=SYNTHETIC_CAMPUS", "--people-qualification.student-number-display-name=Synthetic test namespace");
        db = context.getBean(JdbcTemplate.class); people = context.getBean(ManagedPersonService.class);
        identifiers = context.getBean(ManagedPersonIdentifierService.class); bindings = context.getBean(ManagedPersonBindingService.class);
        auth = context.getBean(PersonFoundationDatabaseHttpTest.TestAuthentication.class);
        base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        addUser(1); addUser(2); addUser(9);
        db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=1");
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code IN ('authorization:read','authorization:assignment-manage','authorization:root-manage','audit-log:read')");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='ROOT'");
        String assignment = key(), change = key();
        db.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,1,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),?,?)", assignment, key(), change);
        var mapper = context.getBean(cn.jualn.miniapp.module.authorization.mapper.RbacMapper.class);
        var assigned = mapper.assignments(1).get(0);
        var receipt = new cn.jualn.miniapp.module.authorization.model.RbacModels.Receipt(change, key(), "GRANT", assigned.getGrantedAt(), mapper.subject(1).getVersion(), assigned);
        db.update("INSERT INTO rbac_authorization_change(id,operation_id,actor_user_id,target_user_id,assignment_id,action,idempotency_key,request_fingerprint,reason,snapshot_version,after_snapshot,receipt,occurred_at) VALUES(?,?,2,1,?,'GRANT',?,REPEAT('a',64),'synthetic existing root',1,?,?,?)",
                change, receipt.operationId(), assignment, key(), json.writeValueAsString(assigned), json.writeValueAsString(receipt), assigned.getGrantedAt());
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(people));
        var metadata = new LinkedHashMap<String, Object>();
        for (String table : List.of("person_profile", "person_major_class_registration", "person_identifier", "person_identifier_occupation", "person_identifier_value_history", "person_account_binding", "person_account_binding_slot")) {
            metadata.put(table, db.queryForMap("SHOW CREATE TABLE " + table));
        }
        metadata.put("serverUuid", admin.queryForObject("SELECT @@server_uuid", String.class)); metadata.put("schema", schema);
        metadata.put("flyway", db.queryForList("SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"));
        Files.createDirectories(Path.of("target/independent-person"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/independent-person/schema.json").toFile(), metadata);
    }
    @AfterAll void stop() throws Exception {
        if (context != null) context.close();
        Files.createDirectories(Path.of("target/independent-person"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/independent-person/http.json").toFile(), evidence);
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/person-roster-projections.json").toFile(),rosterProjections);
    }
    @BeforeEach void reset() { auth.denied.clear(); auth.onRequire = null; loseCommit.set(false); }
    @Test void independentProfileHttpAndReplayKeepOriginalResult() throws Exception {
        long users = count("user_profile"); String key = key();
        var created = wire("POST", "/v1/admin/persons", "{\"realName\":\"  Shared Name  \",\"reason\":\"source checked\",\"verificationReference\":\"synthetic\"}", key, null, "application/json", 201);
        String person = created.body.path("personId").asText(); assertFalse(person.matches("[0-9]+"));
        assertEquals("Shared Name", created.body.path("realName").asText()); assertEquals(users, count("user_profile"));
        var old = people.getManagedPerson(1, person); String changeKey = key(); var command = new NameChange("Corrected", "correct", "source");
        var changed = people.updateManagedPersonName(1, person, command, changeKey, old.etag());
        assertEquals(changed, people.updateManagedPersonName(1, person, command, changeKey, old.etag()));
        assertEquals(2, people.listManagedPersonHistory(1, person, 1, 100).totalItems());
        var recovered = bindings.getMyPersonCommandReceipt(1, "createManagedPerson", "PERSON_DIRECTORY", key);
        assertEquals("Shared Name", ((Profile) recovered.get("result")).realName());
        assertEquals("Corrected", people.getManagedPerson(1, person).value().realName());
        auth.denied.add("identity:read"); assertThrows(ContractProblemException.class, () -> people.updateManagedPersonName(1, person, command, changeKey, old.etag()));
        auth.denied.clear();
        wire("PATCH", "/v1/admin/persons/" + person, "{\"realName\":\"bad\",\"reason\":\"r\",\"verificationReference\":\"s\",\"userId\":\"2\"}", key(), changed.etag(), "application/json", 400);
    }
    @Test void mergePatchDistinguishesOmissionClearAndBlank() throws Exception {
        var person = create("Fields"); String id = person.value().personId();
        var initial = people.getPersonMajorClassRegistration(1, id); long events = count("person_foundation_event");
        var omitted = people.updatePersonMajorClassRegistration(1, id, new MajorClassPatch(false, null, "checked", "private"), key(), initial.etag());
        assertEquals(initial.value(), omitted.value()); assertEquals(events, count("person_foundation_event"));
        var set = wire("PATCH", "/v1/admin/persons/" + id + "/major-class-registration", "{\"majorClass\":\"  Class A  \",\"reason\":\"r\",\"verificationReference\":\"private-other-org\"}", key(), initial.etag(), "application/merge-patch+json", 200);
        assertEquals("Class A", set.body.path("majorClass").asText()); assertFalse(set.body.path("provenance").has("sourceReference"));
        wire("PATCH", "/v1/admin/persons/" + id + "/major-class-registration", "{\"majorClass\":\"  \",\"reason\":\"r\",\"verificationReference\":\"s\"}", key(), set.etag, "application/merge-patch+json", 400);
        var cleared = wire("PATCH", "/v1/admin/persons/" + id + "/major-class-registration", "{\"majorClass\":null,\"reason\":\"r\",\"verificationReference\":\"s\"}", key(), set.etag, "application/merge-patch+json", 200);
        assertFalse(cleared.body.has("majorClass")); assertEquals("CLEAR", cleared.body.path("lastAction").asText());
    }
    @Test void identifierCaseLeadingZeroCorrectionsAndPermanentOccupancy() {
        var p = create("Identifier"); var q = create("Other"); String id = p.value().personId();
        var registered = identifiers.createPersonIdentifier(1, id, identifier(p, " 00AbC "), key());
        assertEquals("00AbC", identifiers.getPersonIdentifierValue(1, id, registered.value().identifierId(), null).value().value());
        var corrected = identifiers.changePersonIdentifier(1, id, registered.value().identifierId(), new IdentifierChange("CORRECT", "00XYZ", "correct", "source"), key(), registered.etag());
        assertEquals("00AbC", identifiers.getPersonIdentifierValue(1, id, registered.value().identifierId(), registered.value().version()).value().value());
        identifiers.changePersonIdentifier(1, id, registered.value().identifierId(), new IdentifierChange("REVOKE", null, "revoke", "source"), key(), corrected.etag());
        assertThrows(ContractProblemException.class, () -> identifiers.createPersonIdentifier(1, q.value().personId(), identifier(q, "00AbC"), key()));
        assertThrows(ContractProblemException.class, () -> identifiers.createPersonIdentifier(1, q.value().personId(), identifier(q, "00XYZ"), key()));
        assertNotNull(identifiers.createPersonIdentifier(1, q.value().personId(), identifier(q, "00abc"), key()));
        var events = people.listManagedPersonHistory(1, id, 1, 100); assertFalse(events.toString().contains("00AbC"));
    }
    @Test void concurrentIdentifierClaimsHaveOneOwner() throws Exception {
        var p = create("Race A"); var q = create("Race B"); String number = "00R" + key();
        var result = race(() -> identifiers.createPersonIdentifier(1, p.value().personId(), identifier(p, number), key()),
                () -> identifiers.createPersonIdentifier(1, q.value().personId(), identifier(q, number), key()));
        assertEquals(1, result.stream().filter(Result.class::isInstance).count());
        assertEquals(1, result.stream().filter(ContractProblemException.class::isInstance).count());
    }
    @Test void bindingReassociationArchiveAndHistoryDoNotReviveIds() {
        var p = create("Bound A"); var q = create("Bound B"); long a = user(), b = user();
        var first = bindings.createUserPersonBinding(1, bind(p, a), key());
        var second = bindings.createUserPersonBinding(1, bind(q, b), key());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> db.update(
                "INSERT INTO person_account_binding(id,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by) SELECT ?,user_id,person_id,status,revision,started_at,verification_method,source_reference,started_by FROM person_account_binding WHERE id=?", key(), first.value().bindingId()));
        var command = bind(people.getManagedPerson(1, p.value().personId()), b);
        var replacement = new Reassociate(List.of(new BindingVersion(first.value().bindingId(), first.value().version()), new BindingVersion(second.value().bindingId(), second.value().version())),
                command.personId(), command.userId(), command.personVersion(), command.personBindingVersion(), command.userBindingVersion(), "reassociate", "source");
        var moved = bindings.reassociateUserPersonBinding(1, replacement, key());
        assertEquals("UNBOUND", bindings.getMyPersonBinding(a).value().state());
        assertEquals(p.value().personId(), bindings.getMyPersonBinding(b).value().personId());
        assertNotEquals(first.value().bindingId(), moved.value().binding().bindingId());
        var current = people.getManagedPerson(1, p.value().personId());
        var archived = people.changeManagedPersonLifecycle(1, p.value().personId(), new Lifecycle("ARCHIVE", "archive", "source"), key(), current.etag());
        assertEquals("UNBOUND", bindings.getMyPersonBinding(b).value().state());
        people.changeManagedPersonLifecycle(1, p.value().personId(), new Lifecycle("RESTORE", "restore", "source"), key(), archived.etag());
        assertEquals("UNBOUND", bindings.getMyPersonBinding(b).value().state());
        assertEquals("ENDED", bindings.getUserPersonBinding(1, first.value().bindingId()).value().status());
        assertThrows(ContractProblemException.class, () -> people.updateManagedPersonName(b, p.value().personId(), new NameChange("self", "r", "s"), key(), current.etag()));
    }
    @Test void receiptFailureRollsBackProfileAndEvents() {
        long profiles = count("person_profile"), events = count("person_foundation_event");
        db.execute("CREATE TRIGGER managed_receipt_failure BEFORE INSERT ON person_command_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic receipt failure'");
        try { assertThrows(RuntimeException.class, () -> create("Must rollback")); }
        finally { db.execute("DROP TRIGGER managed_receipt_failure"); }
        assertEquals(profiles, count("person_profile")); assertEquals(events, count("person_foundation_event"));
    }
    @Test void lostCommitAcknowledgementRecoversOriginalCommand() {
        var command = new Create("Unknown outcome", null, "r", "s"); String key = key(); long count = count("person_profile");
        loseCommit.set(true); assertThrows(RuntimeException.class, () -> people.createManagedPerson(1, command, key));
        loseCommit.set(false);
        var recovered = bindings.getMyPersonCommandReceipt(1, "createManagedPerson", "PERSON_DIRECTORY", key);
        assertEquals("SUCCEEDED", recovered.get("state"));
        var replay = people.createManagedPerson(1, command, key);
        assertEquals(recovered.get("result"), replay.value()); assertEquals(count + 1, count("person_profile"));
    }
    @Test void concurrentBindingAndArchiveCannotLeaveArchivedBinding() throws Exception {
        var p = create("Archive race"); long user = user(); var command = bind(p, user);
        race(() -> bindings.createUserPersonBinding(1, command, key()), () -> people.changeManagedPersonLifecycle(1, p.value().personId(), new Lifecycle("ARCHIVE", "r", "s"), key(), p.etag()));
        assertEquals("ARCHIVED", people.getManagedPerson(1, p.value().personId()).value().status());
        assertEquals("UNBOUND", bindings.getMyPersonBinding(user).value().state());
    }
    @Test void canonicalAdminOperationsHaveRealHttpRepresentations() throws Exception {
        var created = wire("POST", "/v1/admin/persons", json.writeValueAsString(new Create("HTTP Person", null, "checked", "source")), key(), null, "application/json", 201);
        String person = created.body.path("personId").asText(), path = "/v1/admin/persons/" + person;
        var profile = wire("GET", path, null, null, null, "application/json", 200);
        profile = wire("PATCH", path, json.writeValueAsString(new NameChange("HTTP Corrected", "checked", "source")), key(), profile.etag, "application/json", 200);
        wire("GET", path + "/history", null, null, null, "application/json", 200);
        var field = wire("GET", path + "/major-class-registration", null, null, null, "application/json", 200);
        wire("PATCH", path + "/major-class-registration", "{\"majorClass\":\"Class HTTP\",\"reason\":\"checked\",\"verificationReference\":\"source\"}", key(), field.etag, "application/merge-patch+json", 200);
        wire("GET", "/v1/admin/person-identifier-namespaces", null, null, null, "application/json", 200);
        wire("GET", path + "/identifiers", null, null, null, "application/json", 200);
        var identifier = wire("POST", path + "/identifiers", json.writeValueAsString(new IdentifierCreate("STUDENT_NUMBER", "SYNTHETIC_CAMPUS", "00HTTP" + key(), "VERIFIED", "checked", "source", profile.body.path("version").asText())), key(), null, "application/json", 201);
        String identifierPath = path + "/identifiers/" + identifier.body.path("identifierId").asText();
        wire("GET", identifierPath + "/value", null, null, null, "application/json", 200);
        wire("POST", identifierPath + "/changes", json.writeValueAsString(new IdentifierChange("REVOKE", null, "checked", "source")).replace(",\"value\":null", ""), key(), identifier.etag, "application/json", 200);
        long a = user(), b = user();
        wire("GET", path + "/binding", null, null, null, "application/json", 200);
        wire("GET", "/v1/admin/person-account-bindings/" + a, null, null, null, "application/json", 200);
        var binding = wire("POST", "/v1/admin/user-person-bindings", json.writeValueAsString(bind(people.getManagedPerson(1, person), a)), key(), null, "application/json", 201);
        wire("GET", "/v1/admin/user-person-bindings/" + binding.body.path("bindingId").asText(), null, null, null, "application/json", 200);
        wire("GET", path + "/bindings", null, null, null, "application/json", 200);
        var next = bind(people.getManagedPerson(1, person), b);
        String reassociateKey = key();
        var reassociate = new Reassociate(List.of(new BindingVersion(binding.body.path("bindingId").asText(), binding.body.path("version").asText())), person, next.userId(), next.personVersion(), next.personBindingVersion(), next.userBindingVersion(), "checked", "source");
        var changed = wire("POST", "/v1/admin/user-person-binding-changes", json.writeValueAsString(reassociate), reassociateKey, null, "application/json", 201);
        var ended = wire("GET", "/v1/admin/user-person-bindings/" + changed.body.path("binding").path("bindingId").asText(), null, null, null, "application/json", 200);
        wire("POST", "/v1/admin/user-person-bindings/" + ended.body.path("bindingId").asText() + ":end", json.writeValueAsString(new End("checked", "source")), key(), ended.etag, "application/json", 200);
        wire("GET", "/v1/admin/person-command-receipts?operationId=reassociateUserPersonBinding&target=" + person, null, reassociateKey, null, "application/json", 200);
        var archived = wire("POST", path + "/lifecycle-changes", json.writeValueAsString(new Lifecycle("ARCHIVE", "checked", "source")), key(), profile.etag, "application/json", 200);
        wire("POST", path + "/lifecycle-changes", json.writeValueAsString(new Lifecycle("RESTORE", "checked", "source")), key(), archived.etag, "application/json", 200);
    }
    @Test void v66ImportBuildsAccountlessPeopleClaimOnlyBindsAndReimportPreservesSharedFacts() throws Exception {
        var roster=context.getBean(PartyRosterService.class);
        var foundation=context.getBean(PersonFoundationService.class);
        context.getBean(PersonFoundationDatabaseHttpTest.TestCatalog.class).confirmed=true;
        String org=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("party source","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        String number="00a"+key();long users=count("user_profile");
        importRoster(roster,org,"source name",number,"first class");
        assertEquals(users,count("user_profile"));
        var entry=roster.entries(1,1,20,new cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery(org,null,null,null,null,null)).items().get(0);
        String person=entry.personId();assertNotNull(person);assertEquals("UNBOUND",entry.accountBindingState());
        assertEquals("source name",people.getManagedPerson(1,person).value().realName());
        assertEquals("first class",people.getPersonMajorClassRegistration(1,person).value().majorClass());
        var before=foundation.identity(1,person);var local=foundation.localMembership(1,person,org);
        assertEquals("ROSTER_IMPORT",foundation.identityHistory(1,person,1,20).items().get(0).source());
        assertEquals("PRESENT",local.value().presence());assertEquals(1,foundation.organizationMembers(1,org,1,20).totalItems());
        long user=user();var applied=roster.apply(user,new cn.jualn.miniapp.module.person.bo.QualificationBO.Apply("source name",number),key());
        assertEquals(person,applied.value().personId());assertNotNull(applied.value().bindingId());
        assertEquals(before.value(),foundation.identity(1,person).value());
        assertEquals(local.value(),foundation.localMembership(1,person,org).value());
        assertEquals(person,roster.myIdentity(user).personId());
        var profile=people.getManagedPerson(1,person);
        people.updateManagedPersonName(1,person,new NameChange("shared current name","checked","synthetic"),key(),profile.etag());
        importRoster(roster,foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("second party source","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId(),"shared current name",number,"");
        assertEquals("shared current name",people.getManagedPerson(1,person).value().realName());
        assertEquals("first class",people.getPersonMajorClassRegistration(1,person).value().majorClass());
        assertEquals(before.value().currentRecord(),foundation.identity(1,person).value().currentRecord());
        var mapper=context.getBean(cn.jualn.miniapp.module.person.mapper.PersonMapper.class);
        assertEquals(0,mapper.corruptSource());assertEquals(0,mapper.corruptFacts());
    }
    @Test void v66MentorCanBeManagedWithoutAccountAndUsesCurrentPersonName() {
        var mentors=context.getBean(MentorManagementService.class);var person=create("original person name");String id=person.value().personId();
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='mentor:qualification-manage'");
        var profile=mentors.put(1,id,new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite("mentor position","unit","checked","synthetic"),key(),null,"*");
        var approved=mentors.change(1,id,new cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange("APPROVE",null,"checked","synthetic"),key(),profile.etag());
        assertEquals("ACTIVE",approved.value().qualificationStatus());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_account_binding WHERE person_id=?",Integer.class,id));
        people.updateManagedPersonName(1,id,new NameChange("current person name","checked","synthetic"),key(),person.etag());
        assertEquals("current person name",mentors.get(1,id).value().realName());assertEquals(2,mentors.history(1,id,1,20).totalItems());
        assertEquals(0,context.getBean(cn.jualn.miniapp.module.person.mapper.QualificationMapper.class).mentorCorruption(id));
    }
    @Test void mentorPlanAConfirmsSameSelfBindingWithoutGrantingOrdinaryRestrictions() throws Exception {
        var mentors=context.getBean(MentorManagementService.class);
        var qualification=context.getBean(cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification.class);
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code IN ('mentor:qualification-manage','content:read')");
        String name="PlanA "+key(),number="00"+key(),position="Position "+key(),unit="Unit "+key();
        var original=create(name);String person=original.value().personId();long user=user();
        identifiers.createPersonIdentifier(1,person,identifier(original,number),key());
        String path="/v1/admin/mentors/"+person;
        var profile=wire("PUT",path,json.writeValueAsString(new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite(position,unit,"checked","source")),key(),"*","application/json",201);
        assertEquals("UNBOUND",profile.body.path("bindingState").asText());
        var approved=wire("POST",path+"/qualification-changes",json.writeValueAsString(new cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange("APPROVE",null,"checked","source")),key(),profile.etag,"application/json",200);
        assertEquals("ACTIVE",approved.body.path("qualificationStatus").asText());assertEquals("UNBOUND",approved.body.path("accountAvailability").asText());
        var roster=context.getBean(PartyRosterService.class);
        var self=roster.apply(user,new cn.jualn.miniapp.module.person.bo.QualificationBO.Apply(name,number),key());
        assertEquals(cn.jualn.miniapp.module.post.service.MentorQualification.State.INELIGIBLE,qualification.evaluate(user).state());
        var scopes=context.getBean(PersonBusinessScopes.class);
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(tx->{scopes.lock(false);assertEquals(person,scopes.currentPerson(user));});
        assertEquals(404,assertThrows(ContractProblemException.class,()->mentors.certified(1,user)).getStatus().value());
        var selfBinding=bindings.getUserPersonBinding(1,self.value().bindingId());var command=bind(people.getManagedPerson(1,person),user);
        var confirmed=bindings.reassociateUserPersonBinding(1,new Reassociate(List.of(new BindingVersion(selfBinding.value().bindingId(),selfBinding.value().version())),person,command.userId(),command.personVersion(),command.personBindingVersion(),command.userBindingVersion(),"verified same pair","source"),key());
        assertNotEquals(selfBinding.value().bindingId(),confirmed.value().binding().bindingId());assertEquals("ENDED",bindings.getUserPersonBinding(1,selfBinding.value().bindingId()).value().status());
        assertEquals(approved.body.path("qualificationId").asText(),qualification.evaluate(user).proof().qualificationId());assertEquals(confirmed.value().binding().bindingId(),mentors.certified(1,user).bindingId());
        for(String q:List.of(name,position,unit)) {
            var page=mentors.list(1,1,1,q,"ACTIVE","BOUND");assertEquals(1,page.totalItems());assertEquals(person,page.items().get(0).personId());assertEquals("AVAILABLE",page.items().get(0).accountAvailability());
            assertEquals(0,mentors.list(1,1,1,q,"ACTIVE","UNBOUND").totalItems());assertEquals(0,mentors.list(1,2,1,q,"ACTIVE","BOUND").items().size());
        }
        wire("GET",path,null,null,null,"application/json",200);
        wire("GET","/v1/admin/mentors?q="+java.net.URLEncoder.encode(position,java.nio.charset.StandardCharsets.UTF_8)+"&qualificationStatus=ACTIVE&bindingState=BOUND&pageSize=1",null,null,null,"application/json",200);
        var current=mentors.get(1,person);
        wire("PUT",path,json.writeValueAsString(new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite(position,unit,"checked again","source")),key(),current.etag(),"application/json",200);
        db.update("UPDATE user_profile SET status=3,ban_expire_at=NULL WHERE id=?",user);
        assertEquals("UNAVAILABLE",mentors.get(1,person).value().accountAvailability());assertEquals("UNAVAILABLE",mentors.list(1,1,1,position,"ACTIVE","BOUND").items().get(0).accountAvailability());
        assertFalse(qualification.evaluate(user).valid());db.update("UPDATE user_profile SET status=1 WHERE id=?",user);
        // A plausible method string without the start ledger must be indeterminate.
        db.update("UPDATE person_account_binding SET source_reference='tampered' WHERE id=?",confirmed.value().binding().bindingId());
        assertEquals(cn.jualn.miniapp.module.post.service.MentorQualification.State.UNAVAILABLE,qualification.evaluate(user).state());
        db.update("UPDATE person_account_binding SET source_reference='source' WHERE id=?",confirmed.value().binding().bindingId());
        assertTrue(qualification.evaluate(user).valid());
    }
    @Test void mentorPlanAHistoricalEvidenceHasOneAttributionAndCount() throws Exception {
        var mentors=context.getBean(MentorManagementService.class);var qualification=context.getBean(cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification.class);
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code IN ('mentor:qualification-manage','content:read')");
        var person=create("History");String id=person.value().personId();long author=user(),other=user();
        var profile=mentors.put(1,id,new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite("P","U","checked","source"),key(),null,"*");
        mentors.change(1,id,new cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange("APPROVE",null,"checked","source"),key(),profile.etag());
        bindings.createUserPersonBinding(1,bind(person,author),key());var proof=qualification.evaluate(author).proof();String full=json.writeValueAsString(proof);assertTrue(full.length()<=512);
        long post=userSequence+++20000,comment=userSequence+++30000;String version=key();
        db.update("INSERT INTO post(id,user_id,content,post_type,category_id,status,audit_status,question_version,question_content_locked) VALUES(?,?,'history question','MENTOR_QA',(SELECT MIN(id) FROM post_category),2,1,?,TRUE)",post,other,version);
        db.update("INSERT INTO comment(id,target_type,target_id,user_id,content,status,audit_status,question_version,first_published_at,mentor_attribution) VALUES(?,1,?,?,'answer',1,1,?,NOW(6),?)",comment,post,author,version,full);
        var mapper=context.getBean(cn.jualn.miniapp.module.person.mapper.QualificationMapper.class);
        try(var session=context.getBean(SqlSessionFactory.class).openSession()) {
            var questions=session.getMapper(cn.jualn.miniapp.module.post.mapper.MentorQuestionMapper.class);
            for(String[] evidence:new String[][]{{null,full},{full,null},{full,full},{json.writeValueAsString(Map.of("personId",id,"bindingId",proof.bindingId(),"qualificationId",proof.qualificationId(),"qualificationVersion",proof.qualificationVersion())),full}}) {
                db.update("UPDATE comment SET mentor_evidence=?,mentor_attribution=? WHERE id=?",evidence[0],evidence[1],comment);session.clearCache();
                assertEquals(0,mapper.answerCorruption(id,null));assertEquals(1,mapper.answerCount(id,author));assertEquals(0,mapper.answerCount(id,other));
                assertEquals(Long.toString(comment),mentors.answers(1,id,1,1,author).items().get(0).commentId());assertEquals(0,mentors.answers(1,id,1,1,other).totalItems());
                assertEquals(0,questions.unknownAnswers(post));assertEquals(1,questions.publicAnswers(post,version));
            }
            wire("GET","/v1/admin/mentors/"+id+"/answers?authorUserId="+author,null,null,null,"application/json",200);
            for(String[] evidence:new String[][]{{"{\"personId\":\"conflict\"}",full},{null,null},{"{\"personId\":\""+id+"\"}",null},{"broken",full}}) {
                db.update("UPDATE comment SET mentor_evidence=?,mentor_attribution=? WHERE id=?",evidence[0],evidence[1],comment);session.clearCache();
                assertEquals(1,mapper.answerCorruption(id,null));assertEquals(503,assertThrows(ContractProblemException.class,()->mentors.answers(1,id,1,20)).getStatus().value());
                assertEquals(1,questions.unknownAnswers(post));assertEquals(0,questions.publicAnswers(post,version));
            }
        } finally {db.update("DELETE FROM comment WHERE id=?",comment);db.update("DELETE FROM post WHERE id=?",post);}
    }
    @Test void mentorQaProofUsesRealPersonBindingLedgerAndSerializesRevocation() throws Exception {
        var mentors=context.getBean(MentorManagementService.class);
        var qualification=context.getBean(cn.jualn.miniapp.module.person.service.qualification.PersistentMentorQualification.class);
        var original=create("QA original name");String person=original.value().personId();long first=user(),next=user();
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',unavailable_reason=NULL WHERE code='mentor:qualification-manage'");
        var profile=mentors.put(1,person,new cn.jualn.miniapp.module.person.bo.QualificationBO.ProfileWrite("QA position","QA unit","checked","synthetic"),key(),null,"*");
        var approved=mentors.change(1,person,new cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange("APPROVE",null,"checked","synthetic"),key(),profile.etag());
        var binding=bindings.createUserPersonBinding(1,bind(original,first),key());
        var accepted=qualification.evaluate(first);assertTrue(accepted.valid());
        assertEquals(person,accepted.proof().personId());assertEquals(binding.value().bindingId(),accepted.proof().bindingId());
        assertEquals("QA original name",accepted.proof().profileAtAnswer().realName());
        people.updateManagedPersonName(1,person,new NameChange("QA current name","checked","synthetic"),key(),original.etag());
        assertEquals("QA current name",qualification.evaluate(first).proof().profileAtAnswer().realName());
        assertEquals("QA original name",accepted.proof().profileAtAnswer().realName());
        bindings.endUserPersonBinding(1,binding.value().bindingId(),new End("QA changed account","synthetic"),key(),binding.etag());
        bindings.createUserPersonBinding(1,bind(people.getManagedPerson(1,person),next),key());
        assertFalse(qualification.currentForOriginal(first,person).valid());assertTrue(qualification.evaluate(next).valid());
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var revoking=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var publishing=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(tx->{
                qualification.guardPublication(next,null);assertTrue(qualification.forWrite(next).valid());acquired.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));} catch(InterruptedException e){throw new RuntimeException(e);}return true;
            }));
            assertTrue(acquired.await(10,TimeUnit.SECONDS));
            var revoked=pool.submit(()->{revoking.countDown();return mentors.change(1,person,new cn.jualn.miniapp.module.person.bo.QualificationBO.QualificationChange("REVOKE",null,"checked","synthetic"),key(),approved.etag());});
            assertTrue(revoking.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->revoked.get(200,TimeUnit.MILLISECONDS));
            release.countDown();assertTrue(publishing.get(10,TimeUnit.SECONDS));assertEquals("REVOKED",revoked.get(10,TimeUnit.SECONDS).value().qualificationStatus());
            assertFalse(qualification.evaluate(next).valid());
        } finally {release.countDown();pool.shutdownNow();}
        assertEquals(0,context.getBean(cn.jualn.miniapp.module.person.mapper.PersonMapper.class).corruptSource());
        assertEquals(0,context.getBean(cn.jualn.miniapp.module.person.mapper.QualificationMapper.class).mentorCorruption(person));
    }
    @Test void v66PointsStorageAndServiceKeepPersonOwnerAfterAccountChange() throws Exception {
        // Real owner tables, mapper, service transactions and balance locks. Authority, admission,
        // media retention and notifications are explicit test adapters; no Provider acceptance.
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(DataSource.class));
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setCacheEnabled(false);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/PointsMapper.xml"),new ClassPathResource("mapper/PointsR3Mapper.xml"),new ClassPathResource("mapper/PersonMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var pointsMapper=session.getMapper(cn.jualn.miniapp.module.points.mapper.PointsMapper.class);
        var owner=context.getBean(ManagedPersonMapper.class);var actor=new java.util.concurrent.atomic.AtomicLong();
        var dependencies=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsDependencies.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(dependencies.actor(org.mockito.ArgumentMatchers.any())).thenAnswer(i->actor.get());
        org.mockito.Mockito.doAnswer(i->{var binding=owner.accountBinding(i.getArgument(0,Long.class));
            if(binding==null) throw cn.jualn.miniapp.module.points.PointsProblems.forbidden("ineligible");
            return new cn.jualn.miniapp.module.points.bo.PointsBO.Subject(binding.getPersonId(),binding.getId());
        }).when(dependencies).subject(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(i->owner.everBound(i.getArgument(1,String.class),i.getArgument(0,Long.class))).when(dependencies).self(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(i->{context.getBean(PersonFactReaders.class).open(i.getArgument(1,Long.class));return null;}).when(dependencies).begin(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.<long[]>any());
        org.mockito.Mockito.doNothing().when(dependencies).requireApplication(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishApplication(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doReturn(true).when(dependencies).approvalEligiblePerson(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doNothing().when(dependencies).recheckAcceptance(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).requirePersonManage(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishPerson(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        var profile=create("point owner");String person=profile.value().personId();long a=user(),b=user(),reviewer=user();
        var binding=bindings.createUserPersonBinding(1,bind(profile,a),key());
        var foundation=context.getBean(PersonFoundationService.class);
        String organization=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("accepting unit","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        var membership=foundation.changeLocalMembership(1,person,organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,person,organization).etag());
        org.mockito.Mockito.doReturn(new cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance(organization,membership.value().membershipId())).when(dependencies).acceptance(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(organization));
        var material=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsPrivateMaterials.class);
        var service=new cn.jualn.miniapp.module.points.service.PointsService(pointsMapper,dependencies,java.time.Clock.systemUTC(),material,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"managementAudit",context.getBean(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        actor.set(a);String requestKey=key();var command=new cn.jualn.miniapp.module.points.bo.PointsBO.Create("application","evidence",List.of("synthetic-retained-file"),organization);
        var created=service.create(command,requestKey);assertEquals(created,service.create(command,requestKey));
        assertEquals(person,created.personId());assertEquals(binding.value().bindingId(),created.submittedBindingId());
        actor.set(reviewer);var approved=service.review(created.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,7,"approved"),key());
        assertEquals(7,service.adminLedger(approved.grantLedgerId()).pointsDelta());
        bindings.endUserPersonBinding(1,binding.value().bindingId(),new End("changed account","synthetic"),key(),binding.etag());
        var next=bindings.createUserPersonBinding(1,bind(people.getManagedPerson(1,person),b),key());
        actor.set(b);assertEquals(7,service.summary().balance());assertEquals(person,service.summary().personId());
        assertEquals(a,service.mine(created.applicationId()).applicantUserId());assertEquals(binding.value().bindingId(),service.mine(created.applicationId()).submittedBindingId());
        assertThrows(ContractProblemException.class,()->service.reverse(approved.grantLedgerId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Reversal("self by another account","source"),key()));
        actor.set(a);assertThrows(ContractProblemException.class,service::summary);
        actor.set(reviewer);var reversal=service.reverse(approved.grantLedgerId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Reversal("independent reversal","source"),key());assertEquals(person,reversal.personId());
        actor.set(b);assertEquals(0,service.summary().balance());assertNotEquals(next.value().bindingId(),created.submittedBindingId());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_subject_lock WHERE person_id=?",Integer.class,person));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE person_id=? AND applicant_user_id=? AND submitted_binding_id=?",Integer.class,person,a,binding.value().bindingId()));
    }
    @Test void pointsR6AtomicCorrectionAndHistoricalRead() throws Exception {
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(DataSource.class));
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setCacheEnabled(false);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/PointsMapper.xml"),new ClassPathResource("mapper/PointsR3Mapper.xml"),new ClassPathResource("mapper/PersonMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var pointsMapper=session.getMapper(cn.jualn.miniapp.module.points.mapper.PointsMapper.class);
        var owner=context.getBean(ManagedPersonMapper.class);var actor=new java.util.concurrent.atomic.AtomicLong();
        var dependencies=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsDependencies.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(dependencies.actor(org.mockito.ArgumentMatchers.any())).thenAnswer(i->actor.get());
        org.mockito.Mockito.doAnswer(i->{var binding=owner.accountBinding(i.getArgument(0,Long.class));
            if(binding==null) throw cn.jualn.miniapp.module.points.PointsProblems.forbidden("ineligible");
            return new cn.jualn.miniapp.module.points.bo.PointsBO.Subject(binding.getPersonId(),binding.getId());
        }).when(dependencies).subject(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(i->owner.everBound(i.getArgument(1,String.class),i.getArgument(0,Long.class))).when(dependencies).self(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(i->{context.getBean(PersonFactReaders.class).open(i.getArgument(1,Long.class));return null;}).when(dependencies).begin(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.<long[]>any());
        org.mockito.Mockito.doNothing().when(dependencies).requireApplication(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishApplication(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doReturn(true).when(dependencies).approvalEligiblePerson(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doNothing().when(dependencies).recheckAcceptance(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).requirePersonManage(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishPerson(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        var profile=create("r6 point owner");String person=profile.value().personId();long a=user(),b=user(),reviewer=user();
        var binding=bindings.createUserPersonBinding(1,bind(profile,a),key());
        var foundation=context.getBean(PersonFoundationService.class);
        String organization=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("accepting unit","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        var membership=foundation.changeLocalMembership(1,person,organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,person,organization).etag());
        org.mockito.Mockito.doReturn(new cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance(organization,membership.value().membershipId())).when(dependencies).acceptance(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(organization));
        var material=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsPrivateMaterials.class);
        var service=new cn.jualn.miniapp.module.points.service.PointsService(pointsMapper,dependencies,java.time.Clock.systemUTC(),material,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"managementAudit",context.getBean(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));

        var queries=session.getMapper(cn.jualn.miniapp.module.points.mapper.PointsR3Mapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"queries",queries);
        org.mockito.Mockito.doNothing().when(dependencies).acceptCorrection(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        actor.set(a);
        var created=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("r6","evidence",List.of("synthetic"),organization),key());
        assertEquals("r6 point owner",created.personName());assertEquals("accepting unit",created.acceptingOrganizationName());
        assertEquals("NONE",created.accounting().status());
        actor.set(reviewer);
        var approved=service.review(created.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,7,null),key());
        assertNull(approved.reviewNote());assertEquals(7,approved.accounting().effectivePoints());
        var cmd=new cn.jualn.miniapp.module.points.bo.PointsBO.Adjustment(10,"correct","verified evidence");String intent=key();
        // Fail at notification acceptance AFTER both ledger rows: every local effect must roll back.
        org.mockito.Mockito.doThrow(new IllegalStateException("injected intent failure")).when(dependencies).acceptCorrection(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThrows(IllegalStateException.class,()->service.correct(approved.grantLedgerId(),cmd,intent));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",Integer.class,created.applicationId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM point_request WHERE operation_name='correctPointGrant'",Integer.class));
        org.mockito.Mockito.doNothing().when(dependencies).acceptCorrection(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var correction=service.correct(approved.grantLedgerId(),cmd,intent);
        assertEquals(correction,service.correct(approved.grantLedgerId(),cmd,intent));
        assertEquals(correction,service.correction(correction.correctionId()));
        assertEquals("CORRECTION_GRANT",correction.replacement().kind().name());assertEquals(-7,correction.reversal().pointsDelta());
        assertEquals(correction.correctionId(),correction.reversal().correctionId());assertEquals(10,correction.replacement().pointsDelta());
        var current=service.adminApplication(created.applicationId());
        assertEquals(7,current.awardedPoints());assertEquals(approved.grantLedgerId(),current.grantLedgerId());assertEquals(10,current.accounting().effectivePoints());
        assertEquals("/problems/points-grant-already-reversed",assertThrows(ContractProblemException.class,()->service.correct(approved.grantLedgerId(),cmd,key())).getType());
        assertEquals("/problems/points-correction-unchanged",assertThrows(ContractProblemException.class,()->service.correct(correction.replacement().ledgerId(),cmd,key())).getType());
        assertEquals("/problems/points-reversal-target-invalid",assertThrows(ContractProblemException.class,()->service.correct(correction.reversal().ledgerId(),cmd,key())).getType());
        // Revoke authority before same-key recovery: a receipt cannot bypass current authorization.
        org.mockito.Mockito.doThrow(cn.jualn.miniapp.module.points.PointsProblems.missing()).when(dependencies).requireApplication(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThrows(ContractProblemException.class,()->service.correct(approved.grantLedgerId(),cmd,intent));
        org.mockito.Mockito.doNothing().when(dependencies).requireApplication(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        // No current applicant binding, active stage or member cycle is needed for historical correction.
        bindings.endUserPersonBinding(1,binding.value().bindingId(),new End("exit","synthetic"),key(),binding.etag());
        foundation.changeLocalMembership(1,person,organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.REMOVE,"left","synthetic"),key(),foundation.localMembership(1,person,organization).etag());
        var second=service.correct(correction.replacement().ledgerId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Adjustment(1,"again","source"),key());
        assertEquals(a,second.replacement().applicantUserId());assertEquals(binding.value().bindingId(),second.replacement().submittedBindingId());
        assertEquals(organization,second.replacement().acceptingOrganizationId());assertEquals(membership.value().membershipId(),second.replacement().membershipId());
        var ended=service.reverse(second.replacement().ledgerId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Reversal("final","source"),key());
        assertEquals(-1,ended.pointsDelta());assertEquals("REVOKED",service.adminApplication(created.applicationId()).accounting().status());
        assertThrows(ContractProblemException.class,()->service.correct(second.replacement().ledgerId(),cmd,key()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=? AND kind='GRANT'",Integer.class,created.applicationId()));
        assertEquals(18,pointsMapper.totals(person).granted().intValueExact());assertEquals(18,pointsMapper.totals(person).reversed().intValueExact());
        assertEquals(6,service.applicationLedger(created.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Query(1,2,null,null)).totalItems());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/points-r6"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/points-r6/results.json").toFile(),java.util.Map.of("application",cn.jualn.miniapp.module.points.vo.PointsVO.application(service.adminApplication(created.applicationId())),"correction",cn.jualn.miniapp.module.points.vo.PointsVO.correction(correction),"reversal",cn.jualn.miniapp.module.points.vo.PointsVO.ledger(ended)));
        // Competing corrections to the same effective node: exactly one complete pair wins.
        actor.set(a);var binding2=bindings.createUserPersonBinding(1,bind(people.getManagedPerson(1,person),a),key());
        actor.set(a);var raceApp=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("race","evidence",List.of("synthetic2"),organization),key());
        actor.set(reviewer);var raceApproved=service.review(raceApp.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,5,null),key());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> work=()->{start.await();try {service.correct(raceApproved.grantLedgerId(),cmd,key());return true;}catch(ContractProblemException e){assertEquals("/problems/points-grant-already-reversed",e.getType());return false;}};
            var one=pool.submit(work);var two=pool.submit(work);start.countDown();
            assertNotEquals(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",Integer.class,raceApp.applicationId()));
        } finally {pool.shutdownNow();}
        // An applicant close and an admin approval race on the same pending application.
        actor.set(a);var pendingRace=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("close race","evidence",List.of("race-file"),organization),key());
        var threadActor=new ThreadLocal<Long>();org.mockito.Mockito.when(dependencies.actor(org.mockito.ArgumentMatchers.any())).thenAnswer(i->threadActor.get()==null?actor.get():threadActor.get());
        var competing=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var close=competing.submit(()->{threadActor.set(a);gate.await();try {service.close(pendingRace.applicationId(),"close",key());return true;}catch(ContractProblemException e){assertEquals("/problems/points-application-finalized",e.getType());return false;}finally{threadActor.remove();}});
            var review=competing.submit(()->{threadActor.set(reviewer);gate.await();try {service.review(pendingRace.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,7,null),key());return true;}catch(ContractProblemException e){assertEquals("/problems/points-application-finalized",e.getType());return false;}finally{threadActor.remove();}});
            gate.countDown();assertNotEquals(close.get(20,java.util.concurrent.TimeUnit.SECONDS),review.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var terminal=service.adminApplication(pendingRace.applicationId());assertTrue(List.of("CLOSED","APPROVED").contains(terminal.status().name()));
            assertEquals(terminal.status()==cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED?1:0,db.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE application_id=?",Integer.class,pendingRace.applicationId()));
        } finally {competing.shutdownNow();}
        actor.set(reviewer);long effective=service.adminApplication(raceApp.applicationId()).accounting().effectiveLedgerId();
        var adjusting=java.util.concurrent.Executors.newFixedThreadPool(2);var adjustGate=new java.util.concurrent.CountDownLatch(1);
        try {
            var reverse=adjusting.submit(()->{adjustGate.await();try{service.reverse(effective,new cn.jualn.miniapp.module.points.bo.PointsBO.Reversal("end","source"),key());return true;}catch(ContractProblemException e){assertEquals("/problems/points-grant-already-reversed",e.getType());return false;}});
            var correct=adjusting.submit(()->{adjustGate.await();try{service.correct(effective,new cn.jualn.miniapp.module.points.bo.PointsBO.Adjustment(1,"change","source"),key());return true;}catch(ContractProblemException e){assertEquals("/problems/points-grant-already-reversed",e.getType());return false;}});
            adjustGate.countDown();assertNotEquals(reverse.get(20,java.util.concurrent.TimeUnit.SECONDS),correct.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(List.of(0,1).contains(service.adminApplication(raceApp.applicationId()).accounting().effectivePoints()));
        } finally {adjusting.shutdownNow();}
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new cn.jualn.miniapp.module.points.controller.PointsController(service,dependencies))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class))).build();
        var captured=new java.util.ArrayList<Object>();
        for(String path:List.of("/v1/admin/points/corrections/"+correction.correctionId(),"/v1/admin/points/applications/"+created.applicationId(),"/v1/admin/points/applications/"+created.applicationId()+"/ledger?kind=CORRECTION_GRANT")) {
            var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());
            captured.add(java.util.Map.of("method","GET","path",path,"status",response.getStatus(),"body",json.readTree(response.getContentAsByteArray()),"cacheControl",response.getHeader("Cache-Control")));
        }
        String correctPath="/v1/admin/points/ledger/"+approved.grantLedgerId()+":correct";
        var replayResponse=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(correctPath).header("Idempotency-Key",intent).contentType("application/json").content("{\"correctedPoints\":10,\"reason\":\"correct\",\"basis\":\"verified evidence\"}")).andReturn().getResponse();
        assertEquals(201,replayResponse.getStatus(),replayResponse.getContentAsString());
        captured.add(java.util.Map.of("method","POST","path",correctPath,"status",201,"body",json.readTree(replayResponse.getContentAsByteArray()),"cacheControl",replayResponse.getHeader("Cache-Control")));
        actor.set(a);String myPath="/v1/users/me/points/ledger?applicationId="+created.applicationId();
        var myResponse=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(myPath)).andReturn().getResponse();
        assertEquals(200,myResponse.getStatus());captured.add(java.util.Map.of("method","GET","path",myPath,"status",200,"body",json.readTree(myResponse.getContentAsByteArray()),"cacheControl",myResponse.getHeader("Cache-Control")));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/points-r6/http.json").toFile(),captured);
    }
    @Test void pointsR6UpgradeRetainsHistoricalGrant() throws Exception {
        var profile=create("historical owner");String person=profile.value().personId();long submitted=user(),reviewer=user();
        var binding=bindings.createUserPersonBinding(1,bind(profile,submitted),key());
        var foundation=context.getBean(PersonFoundationService.class);
        String historicalOrg=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("historical historicalOrg","",null,"source","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        var member=foundation.changeLocalMembership(1,person,historicalOrg,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"source","synthetic"),key(),foundation.localMembership(1,person,historicalOrg).etag());
        String currentUrl=System.getProperty("managed.person.test.jdbcUrl"),oldSchema=currentUrl.substring(currentUrl.lastIndexOf('/')+1),upgradeSchema=oldSchema+"_upgrade";
        assertTrue(upgradeSchema.matches("independent_person_test_[a-z0-9_]+"));
        new org.springframework.jdbc.core.JdbcTemplate(source(currentUrl.substring(0,currentUrl.lastIndexOf('/'))+"/mysql")).execute("CREATE DATABASE "+upgradeSchema);
        var upgradeSource=source(currentUrl.substring(0,currentUrl.lastIndexOf('/')+1)+upgradeSchema);
        org.flywaydb.core.Flyway.configure().dataSource(upgradeSource).target("87").load().migrate();
        var old=new org.springframework.jdbc.core.JdbcTemplate(upgradeSource);
        // Copy only explicit fixture dependencies, keeping every FK enabled and generated column owner intact.
        for(String table:List.of("user_profile","person_foundation_event","person_command_receipt","person_profile","person_organization","person_account_binding","person_membership")) {
            var cols=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND extra NOT LIKE '%GENERATED%' ORDER BY ordinal_position",String.class,oldSchema,table);
            String columns=cols.stream().map(c->"`"+c+"`").collect(java.util.stream.Collectors.joining(","));
            old.update("INSERT INTO "+table+"("+columns+") SELECT "+columns+" FROM "+oldSchema+"."+table+" src WHERE NOT EXISTS(SELECT 1 FROM "+table+" dst WHERE dst.id=src.id)");
        }
        old.update("INSERT INTO point_application(person_id,submitted_binding_id,applicant_user_id,title,description,file_ids,status,created_at,accepting_organization_id,membership_id) VALUES(?,?,?,'old','old',JSON_ARRAY('old-file'),'PENDING',UTC_TIMESTAMP(6),?,?)",person,binding.value().bindingId(),submitted,historicalOrg,member.value().membershipId());
        long application=old.queryForObject("SELECT MAX(id) FROM point_application",Long.class);
        old.update("INSERT INTO point_ledger(person_id,submitted_binding_id,applicant_user_id,application_id,kind,points_delta,operator_user_id,reason,created_at,accepting_organization_id,membership_id) VALUES(?,?,?,?,'GRANT',12,?,'historical review',UTC_TIMESTAMP(6),?,?)",person,binding.value().bindingId(),submitted,application,reviewer,historicalOrg,member.value().membershipId());
        long grant=old.queryForObject("SELECT MAX(id) FROM point_ledger",Long.class);
        old.update("UPDATE point_application SET status='APPROVED',reviewer_user_id=?,review_note='historical review',awarded_points=12,grant_ledger_id=?,reviewed_at=UTC_TIMESTAMP(6) WHERE id=?",reviewer,grant,application);
        var upgraded=org.flywaydb.core.Flyway.configure().dataSource(upgradeSource).load();assertEquals(1,upgraded.migrate().migrationsExecuted);upgraded.validate();assertEquals(0,upgraded.migrate().migrationsExecuted);
        assertEquals(12,old.queryForObject("SELECT awarded_points FROM point_application WHERE id=?",Integer.class,application));
        assertEquals("historical review",old.queryForObject("SELECT review_note FROM point_application WHERE id=?",String.class,application));
        assertEquals(12,old.queryForObject("SELECT points_delta FROM point_ledger WHERE id=?",Integer.class,grant));
    }
    @Test void pointsR6PublishedDutySeparatesHistoryMaterialsAndStatistics() throws Exception {
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(DataSource.class));
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setCacheEnabled(false);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/PointsMapper.xml"),new ClassPathResource("mapper/PointsR3Mapper.xml"),new ClassPathResource("mapper/PersonMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var pointsMapper=session.getMapper(cn.jualn.miniapp.module.points.mapper.PointsMapper.class);
        var owner=context.getBean(ManagedPersonMapper.class);var actor=new java.util.concurrent.atomic.AtomicLong();
        var dependencies=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsDependencies.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(dependencies.actor(org.mockito.ArgumentMatchers.any())).thenAnswer(i->actor.get());
        org.mockito.Mockito.doAnswer(i->{var binding=owner.accountBinding(i.getArgument(0,Long.class));
            if(binding==null) throw cn.jualn.miniapp.module.points.PointsProblems.forbidden("ineligible");
            return new cn.jualn.miniapp.module.points.bo.PointsBO.Subject(binding.getPersonId(),binding.getId());
        }).when(dependencies).subject(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(i->owner.everBound(i.getArgument(1,String.class),i.getArgument(0,Long.class))).when(dependencies).self(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(i->{context.getBean(PersonFactReaders.class).open(i.getArgument(1,Long.class));return null;}).when(dependencies).begin(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.<long[]>any());
        org.mockito.Mockito.doNothing().when(dependencies).requireApplication(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishApplication(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doReturn(true).when(dependencies).approvalEligiblePerson(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doNothing().when(dependencies).recheckAcceptance(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).requirePersonManage(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(dependencies).finishPerson(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        var profile=create("r6 point owner");String person=profile.value().personId();long a=user(),b=user(),reviewer=user();
        var binding=bindings.createUserPersonBinding(1,bind(profile,a),key());
        var foundation=context.getBean(PersonFoundationService.class);
        String organization=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("accepting unit","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        var membership=foundation.changeLocalMembership(1,person,organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,person,organization).etag());
        org.mockito.Mockito.doReturn(new cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance(organization,membership.value().membershipId())).when(dependencies).acceptance(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(organization));
        var material=org.mockito.Mockito.mock(cn.jualn.miniapp.module.points.service.PointsPrivateMaterials.class);
        var service=new cn.jualn.miniapp.module.points.service.PointsService(pointsMapper,dependencies,java.time.Clock.systemUTC(),material,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"managementAudit",context.getBean(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));


        actor.set(a);var created=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("scoped","evidence",List.of("synthetic"),organization),key());
        actor.set(reviewer);var approved=service.review(created.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.APPROVED,4,null),key());
        var queries=session.getMapper(cn.jualn.miniapp.module.points.mapper.PointsR3Mapper.class);
        var policy=new cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy(pointsMapper,
                context.getBean(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class),context.getBean(PersonFactReaders.class),
                context.getBean(PersonBusinessScopes.class),context.getBean(cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader.class),queries);
        var unified=context.getBean(cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService.class);
        String root=context.getBean(cn.jualn.miniapp.module.authorization.mapper.RbacMapper.class).assignments(1).get(0).getId();
        db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=?",reviewer);
        String expiry=cn.jualn.miniapp.module.authorization.support.RbacTime.wire(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).withNano(0));
        var selection=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("POINTS_CORRECTION",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),expiry,null);
        var intent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(reviewer),List.of(selection),root,null,null,"independent correction approval");
        var preview=unified.preview(1,intent,key());var granted=unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(preview.previewId(),intent),key());
        // The migration and actual Unified grant supply readiness/capabilities; no test UPDATE enables Points.
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status->{
            var c=context.getBean(PersonFactReaders.class).open(reviewer);
            var current=context.getBean(cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization.class).current(reviewer,c.evaluatedAt());
            assertTrue(current.allows("points:adjust","POINTS"));assertFalse(current.allows("points:review","POINTS"));
            policy.application(reviewer,approved,cn.jualn.miniapp.module.points.bo.PointsBO.Action.CORRECT,c,false);
            policy.application(reviewer,approved,cn.jualn.miniapp.module.points.bo.PointsBO.Action.READ,c,false);
            assertThrows(ContractProblemException.class,()->policy.application(reviewer,approved,cn.jualn.miniapp.module.points.bo.PointsBO.Action.MATERIAL_READ,c,false));
            assertThrows(ContractProblemException.class,()->policy.people(reviewer,person,null,c));
            var plan=policy.applications(reviewer,person,null,new cn.jualn.miniapp.module.points.bo.PointsBO.Query(1,1,null,null),c);
            assertEquals(1,queries.applicationCount(plan,new cn.jualn.miniapp.module.points.bo.PointsBO.Query(1,1,null,null)));
            assertEquals(1,queries.applications(plan,new cn.jualn.miniapp.module.points.bo.PointsBO.Query(1,1,null,null)).size());
        });
        // Four-state counts and overlapping CURRENT/immutable acceptance accounting use real SQL.
        actor.set(a);var closed=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("closed","evidence",List.of("closed-file"),organization),key());service.close(closed.applicationId(),"withdraw",key());
        actor.set(a);var rejected=service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("rejected","evidence",List.of("rejected-file"),organization),key());
        actor.set(reviewer);service.review(rejected.applicationId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Review(cn.jualn.miniapp.module.points.bo.PointsBO.Status.REJECTED,null,null),key());
        actor.set(a);service.create(new cn.jualn.miniapp.module.points.bo.PointsBO.Create("pending","evidence",List.of("pending-file"),organization),key());
        actor.set(reviewer);org.mockito.Mockito.doNothing().when(dependencies).acceptCorrection(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        service.correct(approved.grantLedgerId(),new cn.jualn.miniapp.module.points.bo.PointsBO.Adjustment(6,"correct","source"),key());
        foundation.replaceMemberships(1,person,new cn.jualn.miniapp.module.person.bo.PersonBO.MembershipChange(cn.jualn.miniapp.module.person.bo.PersonBO.Verification.VERIFIED,List.of(new cn.jualn.miniapp.module.person.bo.PersonBO.MemberInput(organization,null)),"complete","synthetic"),key(),foundation.memberships(1,person).etag());
        String empty=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("empty unit","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        for(String org:List.of(organization,empty)) {var roster=foundation.rosterCompleteness(1,org);foundation.changeRosterCompleteness(1,org,new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("CONFIRM",roster.value().membershipVersion(),"complete roster","synthetic"),key(),roster.etag());}
        long statistician=user();db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id=?",statistician);
        var selections=List.of(
            new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("POINTS_PERSON_LEDGER",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PERSON",person,null),expiry,null),
            new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("POINTS_STATS_CURRENT",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),expiry,null),
            new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("POINTS_STATS_CURRENT",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,empty),expiry,null),
            new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("POINTS_STATS_ACCEPTING",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),expiry,null));
        var statisticsIntent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(statistician),selections,root,null,null,"bounded statistics approval");
        var statisticsPreview=unified.preview(1,statisticsIntent,key());unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(statisticsPreview.previewId(),statisticsIntent),key());
        transaction.executeWithoutResult(status->{
            var c=context.getBean(PersonFactReaders.class).open(statistician);var q=new cn.jualn.miniapp.module.points.bo.PointsBO.Query(1,1,null,null);
            var personRow=queries.personStatistics(policy.people(statistician,person,null,c),q).get(0);
            assertEquals(4,personRow.applicationCount());assertEquals(1,personRow.closedCount());assertEquals(1,personRow.pendingCount());assertEquals(1,personRow.rejectedCount());assertEquals(1,personRow.approvedCount());
            assertEquals(10,personRow.granted().intValueExact());assertEquals(4,personRow.reversed().intValueExact());
            var plan=policy.statistics(statistician,null,false,c);
            assertEquals(1,queries.currentOrganizationCount(plan));assertEquals(1,queries.currentStatistics(plan,q).size());assertTrue(queries.currentStatistics(plan,new cn.jualn.miniapp.module.points.bo.PointsBO.Query(2,1,null,null)).isEmpty());
            var accepted=queries.acceptanceStatistics(policy.statistics(statistician,organization,true,c),q).get(0);assertEquals(1,accepted.applicationCount());assertEquals(10,accepted.granted().intValueExact());assertEquals(4,accepted.reversed().intValueExact());
            // Capture the generated scoped report plan on the owned fixture (small cardinality only).
            var params=java.util.Map.<String,Object>of("p",plan,"q",q);var bound=configuration.getMappedStatement("cn.jualn.miniapp.module.points.mapper.PointsR3Mapper.currentStatistics").getBoundSql(params);
            var values=new java.util.ArrayList<Object>();var meta=configuration.newMetaObject(params);
            for(var mapping:bound.getParameterMappings()) values.add(bound.hasAdditionalParameter(mapping.getProperty())?bound.getAdditionalParameter(mapping.getProperty()):meta.getValue(mapping.getProperty()));
            try {java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/points-r6"));json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/points-r6/current-statistics-explain.json").toFile(),db.queryForList("EXPLAIN "+bound.getSql(),values.toArray()));}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        });
        // An inactive original organization and an archived beneficiary retain historical correction scope.
        foundation.updateOrganization(1,organization,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange(null,null,"INACTIVE","closed","synthetic",null),key(),foundation.organization(1,organization).etag());
        people.changeManagedPersonLifecycle(1,person,new Lifecycle("ARCHIVE","exit","synthetic"),key(),people.getManagedPerson(1,person).etag());
        transaction.executeWithoutResult(status->{var c=context.getBean(PersonFactReaders.class).open(reviewer);policy.application(reviewer,approved,cn.jualn.miniapp.module.points.bo.PointsBO.Action.CORRECT,c,false);});
        var revoke=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("REVOKE",null,null,null,"SCOPE",granted.scopeGrantIds().get(0),"withdraw correction");
        var revokePreview=unified.preview(1,revoke,key());unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(revokePreview.previewId(),revoke),key());
        transaction.executeWithoutResult(status->{var c=context.getBean(PersonFactReaders.class).open(reviewer);assertThrows(ContractProblemException.class,()->policy.application(reviewer,approved,cn.jualn.miniapp.module.points.bo.PointsBO.Action.CORRECT,c,false));});
    }
    @Test void v66LearningPreservesTwoResponsibilitiesAndRecordsActualConfirmationBinding() throws Exception {
        // Real MySQL, responsibility resolver and task service. Session/scope, text safety and
        // notification delivery are explicit test adapters, so this is not Provider acceptance.
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(context.getBean(DataSource.class));
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/LearningTaskMapper.xml"),new ClassPathResource("mapper/LearningAssignmentMapper.xml"),new ClassPathResource("mapper/LearningSubmissionMapper.xml"),new ClassPathResource("mapper/LearningPreviewMapper.xml"),new ClassPathResource("mapper/LearningTaskLedgerMapper.xml"),new ClassPathResource("mapper/UserProfileMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var tasks=session.getMapper(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper.class);
        var assignments=session.getMapper(cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper.class);
        var submissions=session.getMapper(cn.jualn.miniapp.module.learningtask.mapper.LearningSubmissionMapper.class);
        var previews=session.getMapper(cn.jualn.miniapp.module.learningtask.mapper.LearningPreviewMapper.class);
        var ledger=session.getMapper(cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper.class);
        var recipients=new cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService(session.getMapper(cn.jualn.miniapp.module.user.mapper.UserProfileMapper.class));
        var responsibility=new cn.jualn.miniapp.module.learningtask.service.LearningTaskResponsibilities(context.getBean(PersonBusinessScopes.class),recipients);
        var actor=new java.util.concurrent.atomic.AtomicLong(1);
        var providers=org.mockito.Mockito.mock(cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders.class);
        org.mockito.Mockito.when(providers.authorize(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->{
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) context.getBean(PersonBusinessScopes.class).lock(false);return actor.get();});
        org.mockito.Mockito.when(providers.audienceVersion(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong())).thenAnswer(i->Long.toString(context.getBean(PersonBusinessScopes.class).epoch()));
        org.mockito.Mockito.when(providers.recipient(org.mockito.ArgumentMatchers.any())).thenAnswer(i->responsibility.recipient(i.getArgument(0)));
        org.mockito.Mockito.when(providers.own(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->responsibility.own(i.getArgument(0),i.getArgument(1)));
        org.mockito.Mockito.when(providers.resolve(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->{
            var audience=i.getArgument(2,cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience.class);
            var selected=new ArrayList<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient>();
            for(var person:audience.personIds()) selected.add(responsibility.recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.person(person)));
            for(var account:audience.accountUserIds()) selected.add(responsibility.recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject.account(Long.parseLong(account))));return selected;
        });
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var service=new cn.jualn.miniapp.module.learningtask.service.impl.LearningTaskServiceImpl(tasks,assignments,submissions,previews,ledger,providers,json,java.time.Clock.systemUTC(),context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"managementAudit",context.getBean(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class));
        var p=create("learning person");var unbound=create("unbound preview person");long a=user(),b=user();
        var original=bindings.createUserPersonBinding(1,bind(p,a),key());
        var requirements=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements(true,0,0,0,List.of());
        var audience=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience(List.of(p.value().personId(),unbound.value().personId()),List.of(Long.toString(a)),List.of(),null);
        var save=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save("subject preview","checked",null,requirements,false,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(2),audience,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("PLATFORM",null,null));
        var blockedTask=service.create(save,key());
        var blocked=transaction.execute(status->service.preview(blockedTask.task().taskId(),new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewRequest(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose.PUBLISH,audience),cn.jualn.miniapp.module.learningtask.LearningTaskEtags.task(blockedTask.task()),key()));
        assertFalse(blocked.preview().canApply());assertTrue(blocked.preview().members().stream().anyMatch(m->"NO_BINDING".equals(m.availability())));assertTrue(blocked.preview().members().stream().anyMatch(m->"DUPLICATE_RESPONSIBILITY".equals(m.availability())));
        assertThrows(ContractProblemException.class,()->transaction.execute(status->service.apply(blockedTask.task().taskId(),new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Apply(blocked.preview().previewId(),null),cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose.PUBLISH,cn.jualn.miniapp.module.learningtask.LearningTaskEtags.task(blockedTask.task()),key())));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,blockedTask.task().taskId()));
        var validAudience=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience(List.of(p.value().personId()),List.of(Long.toString(b)),List.of(),null);
        var validSave=new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save("two responsibilities","checked",null,requirements,false,save.firstDeadline(),validAudience,save.managementTarget());
        var task=service.create(validSave,key());long taskId=task.task().taskId();String taskTag=cn.jualn.miniapp.module.learningtask.LearningTaskEtags.task(task.task());
        var preview=transaction.execute(status->service.preview(taskId,new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewRequest(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose.PUBLISH,validAudience),taskTag,key()));
        assertTrue(preview.preview().canApply());transaction.execute(status->service.apply(taskId,new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Apply(preview.preview().previewId(),null),cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose.PUBLISH,taskTag,key()));
        long personAssignment=db.queryForObject("SELECT id FROM learning_task_assignment WHERE task_id=? AND subject_type='PERSON'",Long.class,taskId);
        long accountAssignment=db.queryForObject("SELECT id FROM learning_task_assignment WHERE task_id=? AND subject_type='ACCOUNT'",Long.class,taskId);
        bindings.endUserPersonBinding(1,original.value().bindingId(),new End("changed account","synthetic"),key(),original.etag());
        var current=bindings.createUserPersonBinding(1,bind(people.getManagedPerson(1,p.value().personId()),b),key());
        actor.set(a);assertThrows(ContractProblemException.class,()->transaction.execute(status->service.mine(taskId,personAssignment)));
        actor.set(b);
        for(long assignment:List.of(personAssignment,accountAssignment)) {
            var before=transaction.execute(status->service.mine(taskId,assignment));
            var confirmed=transaction.execute(status->service.confirm(taskId,assignment,cn.jualn.miniapp.module.learningtask.LearningTaskEtags.assignment(before.task(),before.assignment()),key()));
            assertEquals(b,confirmed.assignment().confirmedByUserId());
        }
        assertEquals(current.value().bindingId(),db.queryForObject("SELECT confirmed_binding_id FROM learning_task_assignment WHERE id=?",String.class,personAssignment));
        assertNull(db.queryForObject("SELECT confirmed_binding_id FROM learning_task_assignment WHERE id=?",String.class,accountAssignment));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM learning_task_assignment WHERE task_id=?",Integer.class,taskId));
    }
    @Test void v66LocalMemberFieldAuthorityDoesNotGrantWholePersonOrExposeExternalResolution() throws Exception {
        var foundation=context.getBean(PersonFoundationService.class);var roster=context.getBean(PartyRosterService.class);
        context.getBean(PersonFoundationDatabaseHttpTest.TestCatalog.class).confirmed=true;
        String external=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("external source","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        String local=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("local source","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        String number="0X"+key();importRoster(roster,external,"external source name",number,"external class");
        String person=roster.entries(1,1,20,new cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery(external,null,null,null,null,null)).items().get(0).personId();
        long manager=user();
        for(var domain:List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.IDENTITY,cn.jualn.miniapp.module.person.bo.PersonBO.Domain.ORGANIZATION))
            foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange(Long.toString(manager),new cn.jualn.miniapp.module.person.bo.PersonBO.Target("ORGANIZATION",null,local),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(domain,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ,cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN)),null,"bounded local source","synthetic"),key());
        importRoster(roster,manager,local,"external source name",number,"");
        var entry=roster.entries(manager,1,20,new cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery(local,null,null,null,null,null)).items().get(0);
        assertEquals(person,entry.personId());assertNull(entry.personIdentity());assertNull(entry.accountBindingState());
        var registration=people.getPersonMajorClassRegistration(manager,person,local);assertEquals("external class",registration.value().majorClass());assertNull(registration.value().provenance().sourceReference());
        assertThrows(ContractProblemException.class,()->people.getManagedPerson(manager,person));
        var updated=people.updatePersonMajorClassRegistration(manager,person,local,new MajorClassPatch(true,"confirmed shared class","checked","synthetic"),key(),registration.etag());
        assertEquals(Long.toString(manager),updated.value().provenance().confirmedByUserId());
        assertEquals("external source name",people.getManagedPerson(1,person).value().realName());
        foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange(Long.toString(manager),new cn.jualn.miniapp.module.person.bo.PersonBO.Target("PERSON",person,null),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.IDENTITY,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ)),null,"explicit independent Person read","synthetic"),key());
        assertEquals(person,people.getManagedPerson(manager,person).value().personId());
    }
    @Test void v66DependentDutyPinsBindingWhileIndependentAccountDutySurvives() throws Exception {
        var foundation=context.getBean(PersonFoundationService.class);
        String organization=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("duty cycle","",null,"checked","synthetic","PARTY_BRANCH"),key()).value().organizationId();
        var rootPerson=create("root fixture person");
        foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange("2",new cn.jualn.miniapp.module.person.bo.PersonBO.Target("PERSON",rootPerson.value().personId(),null),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.IDENTITY,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ,cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN)),null,"independent fixture control","synthetic"),key());
        foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange("2",new cn.jualn.miniapp.module.person.bo.PersonBO.Target("ORGANIZATION",null,organization),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.ORGANIZATION,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ,cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN)),null,"independent fixture control","synthetic"),key());
        bindings.createUserPersonBinding(2,bind(rootPerson,1),key());
        foundation.changeLocalMembership(2,rootPerson.value().personId(),organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,rootPerson.value().personId(),organization).etag());
        long manager=user(),grantee=user();var managerPerson=create("manager person");var granteePerson=create("grantee person");
        db.update("UPDATE rbac_authorization_authority SET source='ASSIGNMENT' WHERE user_id IN (?,?)",manager,grantee);
        bindings.createUserPersonBinding(1,bind(managerPerson,manager),key());var original=bindings.createUserPersonBinding(1,bind(granteePerson,grantee),key());
        for(var person:List.of(managerPerson,granteePerson)) foundation.changeLocalMembership(1,person.value().personId(),organization,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"checked","synthetic"),key(),foundation.localMembership(1,person.value().personId(),organization).etag());
        db.update("UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code IN ('identity:read','identity:map-manage','organization:read','organization:map-manage')");
        db.update("UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='PERSON_FACT_STEWARD_V1'");
        var unified=context.getBean(cn.jualn.miniapp.module.authorization.service.UnifiedAuthorizationService.class);
        String expiry=cn.jualn.miniapp.module.authorization.support.RbacTime.wire(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).withNano(0));
        var selection=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("ORG_MEMBER_MAINTAIN",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),expiry,null);
        var authority=unified.grantAuthority(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRequest(Long.toString(manager),List.of(selection),"SAME_ORGANIZATION_MEMBERS",expiry,"explicit same cycle approval"),key());
        var intent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(grantee),List.of(selection),authority.authorityId(),null,null,"cycle appointment");
        var preview=unified.preview(manager,intent,key());var dependent=unified.commit(manager,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(preview.previewId(),intent),key());
        var proof=json.readTree(db.queryForObject("SELECT member_bindings FROM person_business_scope WHERE id=?",String.class,dependent.scopeGrantIds().get(0)));
        assertEquals(granteePerson.value().personId(),proof.get(0).path("personId").asText());assertEquals(original.value().bindingId(),proof.get(0).path("bindingId").asText());assertTrue(proof.get(0).hasNonNull("membershipId"));
        String root=context.getBean(cn.jualn.miniapp.module.authorization.mapper.RbacMapper.class).assignments(1).get(0).getId();
        var independentIntent=new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent("GRANT",Long.toString(grantee),List.of(selection),root,null,null,"independent account approval");
        var independentPreview=unified.preview(1,independentIntent,key());var independent=unified.commit(1,new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit(independentPreview.previewId(),independentIntent),key());
        bindings.endUserPersonBinding(1,original.value().bindingId(),new End("end cycle proof","synthetic"),key(),original.etag());
        var next=bindings.createUserPersonBinding(1,bind(people.getManagedPerson(1,granteePerson.value().personId()),grantee),key());assertNotEquals(original.value().bindingId(),next.value().bindingId());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status->{var scopes=context.getBean(PersonBusinessScopes.class);scopes.lock(false);var reader=context.getBean(cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader.class);
            assertEquals("MEMBERSHIP_ENDED",reader.reason(scopes.scope(dependent.scopeGrantIds().get(0)),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
            assertEquals("EFFECTIVE",reader.reason(scopes.scope(independent.scopeGrantIds().get(0)),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        });
    }
    @Test void v66ExpiryAfterServiceReturnsRollsBackPersonAndCommandReceipt() throws Exception {
        var profile=create("before expiry");long manager=user();String person=profile.value().personId();
        var foundation=context.getBean(PersonFoundationService.class);
        var until=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(2).withNano(0);
        foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange(Long.toString(manager),new cn.jualn.miniapp.module.person.bo.PersonBO.Target("PERSON",person,null),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.IDENTITY,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ,cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN)),until,"expiring approved scope","synthetic"),key());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String requestKey=key();
        assertThrows(ContractProblemException.class,()->transaction.execute(status->{
            people.updateManagedPersonName(manager,person,new NameChange("must roll back","checked","synthetic"),requestKey,profile.etag());
            long delay=java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),until).toMillis()+100;
            if(delay>0) try {Thread.sleep(delay);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}
            return null;
        }));
        assertEquals("before expiry",people.getManagedPerson(1,person).value().realName());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM person_command_receipt WHERE actor=? AND command_key=?",Integer.class,manager,requestKey));
    }
    @Test void v66ScopeRevocationSerializesWithPersonWriteAndClosesReplay() throws Exception {
        var profile=create("before scope race");String person=profile.value().personId();long manager=user();
        var foundation=context.getBean(PersonFoundationService.class);
        var scope=foundation.grant(1,new cn.jualn.miniapp.module.person.bo.PersonBO.ScopeChange(Long.toString(manager),new cn.jualn.miniapp.module.person.bo.PersonBO.Target("PERSON",person,null),new cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet(cn.jualn.miniapp.module.person.bo.PersonBO.Domain.IDENTITY,List.of(cn.jualn.miniapp.module.person.bo.PersonBO.Action.READ,cn.jualn.miniapp.module.person.bo.PersonBO.Action.MAINTAIN)),null,"independent scope for race","synthetic"),key());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);String requestKey=key();var command=new NameChange("committed before revoke","checked","synthetic");
        try {
            var writer=pool.submit(()->{var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return transaction.execute(status->{var result=people.updateManagedPersonName(manager,person,command,requestKey,profile.etag());entered.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}return result;});});
            assertTrue(entered.await(8,TimeUnit.SECONDS));
            var revoke=pool.submit(()->foundation.revoke(1,scope.value().scopeGrantId(),new cn.jualn.miniapp.module.person.bo.PersonBO.Reason("withdraw actual target scope","synthetic"),key(),scope.etag()));
            assertThrows(TimeoutException.class,()->revoke.get(150,TimeUnit.MILLISECONDS));release.countDown();writer.get(8,TimeUnit.SECONDS);revoke.get(8,TimeUnit.SECONDS);
            assertEquals("committed before revoke",people.getManagedPerson(1,person).value().realName());
            assertThrows(ContractProblemException.class,()->people.updateManagedPersonName(manager,person,command,requestKey,profile.etag()));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM person_command_receipt WHERE actor=? AND command_key=?",Integer.class,manager,requestKey));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void v66UpgradeGuardsOldPointsAndPreservesIndependentStorage() throws Exception {
        // Synthetic storage sentinels verify DDL preservation, not historical authorization acceptance.
        String url=System.getProperty("managed.person.test.jdbcUrl"),schema=url.substring(url.lastIndexOf('/')+1).replace("_test_","_upgrade_");
        assertTrue(schema.matches("independent_person_upgrade_[a-z0-9]+"));
        String upgradeUrl=url.substring(0,url.lastIndexOf('/')+1)+schema;
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema));admin.execute("CREATE DATABASE "+schema);
        Flyway.configure().dataSource(source(upgradeUrl)).target("65").load().migrate();
        var old=new JdbcTemplate(source(upgradeUrl));
        old.update("INSERT INTO user_profile(id,openid,nickname) VALUES(9001,'v66-upgrade-operator','preserved operator'),(9002,'v66-upgrade-account','preserved account')");
        String organization=key(),orgEvent=key(),legacyEvent=key(),person=key(),profileEvent=key(),root=key(),assignment=key(),scope=key(),member=key();
        String eventSql="INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id,person_id,after_version) VALUES(?,?,?,1,'SYNTHETIC_STORAGE_SENTINEL','{}',9001,UTC_TIMESTAMP(6),'synthetic storage test','synthetic',?,?,?,?)";
        old.update(eventSql,orgEvent,"ORGANIZATION",organization,"LOCAL_MANUAL",key(),null,null);
        old.update(eventSql,legacyEvent,"PERSON","9002","LOCAL_MANUAL",key(),null,null);
        old.update(eventSql,profileEvent,"PROFILE",person,"LOCAL_MAINTENANCE",key(),person,"profile:"+person+":1");
        old.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id) VALUES(?,'preserved organization','synthetic','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",organization,orgEvent);
        old.update("INSERT INTO person_profile(id,real_name,status,revision,created_at,updated_at,last_change_id) VALUES(?,'preserved independent Person','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",person,profileEvent);
        old.update("INSERT INTO person_major_class_registration(person_id,major_class,last_action,source_kind,source_reference,confirmed_by,confirmed_at,revision) VALUES(?,'preserved shared class','SET','ADMIN_REGISTRATION','private synthetic reference',9001,UTC_TIMESTAMP(6),1)",person);
        old.update("INSERT INTO person_fact_current(user_id,revision,last_change_id) VALUES(9002,1,?)",legacyEvent);
        old.update("INSERT INTO person_membership(id,user_id,organization_id,valid_from,created_event_id) VALUES(?,9002,?,UTC_TIMESTAMP(6),?)",member,organization,legacyEvent);
        old.update("INSERT INTO person_local_membership(user_id,organization_id,presence,membership_id,verified_by,verified_at,last_event_id) VALUES(9002,?,'PRESENT',?,9001,UTC_TIMESTAMP(6),?)",organization,member,legacyEvent);
        old.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id) VALUES(?,9001,'ROOT','AUTHORIZATION','GLOBAL',UTC_TIMESTAMP(6),?,?)",root,key(),key());
        old.update("INSERT INTO rbac_user_role_assignment(id,user_id,role_code,namespace,scope_type,granted_at,version,grant_change_id,current_slot,origin_kind) VALUES(?,9002,'PERSON_FACT_STEWARD_V1','ORGANIZATION','GLOBAL',UTC_TIMESTAMP(6),?,?,NULL,'UNIFIED')",assignment,key(),key());
        String selection=json.writeValueAsString(new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection("ORG_MEMBER_MAINTAIN",new cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target("ORGANIZATION",null,organization),"2099-01-01T00:00:00Z",null));
        old.update("INSERT INTO person_business_scope(id,grantee_user_id,duty_code,assignment_id,root_assignment_id,selection,member_bindings,granted_by,granted_at,expires_at,change_id) VALUES(?,9002,'ORG_MEMBER_MAINTAIN',?,?,?,'[]',9001,UTC_TIMESTAMP(6),'2099-01-01',?)",scope,assignment,root,selection,key());
        String requirements="{\"confirmationRequired\":true,\"minimumTextLength\":0,\"minimumFiles\":0,\"maximumFiles\":0,\"allowedFileKinds\":[]}";
        old.update("INSERT INTO learning_task(id,title,description,requirements,audience,review_required,first_deadline,status,created_by,created_at,updated_at,published_at,management_target) VALUES(8001,'preserved task','preserved text',?,JSON_OBJECT('userIds',JSON_ARRAY('9002'),'filters',JSON_ARRAY(),'authorizedScopeId',NULL),0,'2099-01-01','PUBLISHED',9001,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),JSON_OBJECT('type','PLATFORM'))",requirements);
        old.update("INSERT INTO learning_task_assignment(id,task_id,user_id,allocation_status,progress,revision,assigned_at,assigned_by,confirmed_at,completed_at) VALUES(8002,8001,9002,'ACTIVE','COMPLETED',1,UTC_TIMESTAMP(6),9001,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        old.update("INSERT INTO learning_task_submission(id,task_id,assignment_id,attempt,text,file_ids,confirmed_at,submitted_at,review_status,revision) VALUES(8003,8001,8002,1,'preserved account submission','[]',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'NOT_REQUIRED',1)");
        old.update("INSERT INTO point_application(applicant_user_id,title,description,file_ids,status,created_at) VALUES(9002,'guard sentinel','guard sentinel',JSON_ARRAY('synthetic'),'PENDING',UTC_TIMESTAMP(6))");
        var preserved=new LinkedHashMap<String,String>();
        for(var table:List.of("user_profile","person_profile","person_major_class_registration","rbac_user_role_assignment","person_business_scope")) preserved.put(table,json.writeValueAsString(old.queryForList("SELECT * FROM "+table+" ORDER BY id".replace(" ORDER BY id",table.equals("person_major_class_registration")?" ORDER BY person_id":" ORDER BY id"))));
        var upgrade=Flyway.configure().dataSource(source(upgradeUrl)).load();assertThrows(org.flywaydb.core.api.FlywayException.class,upgrade::migrate);
        assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='person_fact_current' AND column_name='user_id'",Integer.class));
        assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM point_application",Integer.class));
        old.update("DELETE FROM point_application WHERE applicant_user_id=9002");upgrade.repair();upgrade.migrate();upgrade.validate();
        for(var table:preserved.keySet()) assertEquals(preserved.get(table),json.writeValueAsString(old.queryForList("SELECT * FROM "+table+(table.equals("person_major_class_registration")?" ORDER BY person_id":" ORDER BY id"))),table);
        assertEquals("UNCLASSIFIED",old.queryForObject("SELECT type FROM person_organization WHERE id=?",String.class,organization));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM person_fact_current",Integer.class));assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM person_membership",Integer.class));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM person_foundation_event WHERE id=?",Integer.class,legacyEvent));
        assertEquals("ACCOUNT",old.queryForObject("SELECT subject_type FROM learning_task_assignment WHERE id=8002",String.class));
        assertEquals(9002L,old.queryForObject("SELECT confirmed_by_user_id FROM learning_task_assignment WHERE id=8002",Long.class));
        assertNull(old.queryForObject("SELECT confirmed_binding_id FROM learning_task_assignment WHERE id=8002",String.class));
        assertEquals(9002L,old.queryForObject("SELECT submitted_by_user_id FROM learning_task_submission WHERE id=8003",Long.class));
        assertEquals("ACCOUNT:9002",old.queryForObject("SELECT responsibility_key FROM learning_task_submission WHERE id=8003",String.class));
        assertEquals("preserved account submission",old.queryForObject("SELECT text FROM learning_task_submission WHERE id=8003",String.class));
        Files.createDirectories(Path.of("target/independent-person/v66"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/independent-person/v66/upgrade-verification.json").toFile(),Map.of("status","PASS","schema",schema,"preservedTables",preserved.keySet(),"legacyPointsGuard","rejected before destructive DDL","oldAccountResponsibility","retained as ACCOUNT with original User proof","boundary","Real MySQL storage sentinels only; not acceptance of historical authorization"));
    }
    void importRoster(PartyRosterService roster,String org,String name,String number,String major) throws Exception {importRoster(roster,1,org,name,number,major);}
    void importRoster(PartyRosterService roster,long actor,String org,String name,String number,String major) throws Exception {
        byte[] bytes;
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new java.io.ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("source");var header=sheet.createRow(0);var row=sheet.createRow(1);
            String[] titles={"name","number","major","stage"},values={name,number,major,"activist"};
            for(int i=0;i<4;i++) {header.createCell(i).setCellValue(titles[i]);row.createCell(i).setCellValue(values[i]);}
            workbook.write(output);bytes=output.toByteArray();
        }
        var uploaded=roster.upload(actor,org,"synthetic source","source.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes,key());
        var mapping=new cn.jualn.miniapp.module.person.bo.QualificationBO.Mapping(0,1,new cn.jualn.miniapp.module.person.bo.QualificationBO.Columns(1,2,3,4),List.of(new cn.jualn.miniapp.module.person.bo.QualificationBO.StageValue("activist","PARTY_ACTIVIST")));
        var mapped=roster.map(actor,uploaded.value().importId(),mapping,key(),uploaded.etag());
        var preview=roster.rows(actor,uploaded.value().importId(),1,20);
        if(actor!=1) {assertEquals("LOCAL_ONLY",preview.value().items().get(0).resolution().visibility());assertNull(preview.value().items().get(0).resolution().personId());assertNull(preview.value().items().get(0).resolution().personAction());}
        var command=new cn.jualn.miniapp.module.person.bo.QualificationBO.Commit("checked","synthetic",true,preview.value().planId());
        String commitKey=key();var committed=roster.commit(actor,uploaded.value().importId(),command,commitKey,mapped.etag());
        assertEquals(committed,roster.commit(actor,uploaded.value().importId(),command,commitKey,mapped.etag()));
    }
    @Test void rosterRevisionDirectoryFiltersBeforeCountAndUsesControlledDisambiguation() throws Exception {
        String name="Directory "+key();var first=people.createManagedPerson(1,new Create(name,"Class one","checked","source"),key());
        create("Other "+key());var foundation=context.getBean(PersonFoundationService.class);
        var page=foundation.persons(1,1,1,null,name,"ACTIVE");assertEquals(1,page.totalItems());assertEquals(1,page.items().size());
        assertEquals("Class one",page.items().get(0).disambiguation());
        wire("GET","/v1/admin/persons?q="+java.net.URLEncoder.encode(name,java.nio.charset.StandardCharsets.UTF_8)+"&status=ACTIVE&pageSize=1",null,null,null,"application/json",200);
        people.changeManagedPersonLifecycle(1,first.value().personId(),new Lifecycle("ARCHIVE","checked","source"),key(),first.etag());
        assertEquals(0,foundation.persons(1,1,20,null,name,"ACTIVE").totalItems());
        assertEquals(1,foundation.persons(1,1,20,null,name,"ARCHIVED").totalItems());
    }
    @Test void rosterRevisionSelfAssociationCompetesAndDoesNotReviveOldBinding() throws Exception {
        String name="Claim "+key(),number="00"+key();var profile=create(name);String person=profile.value().personId();
        identifiers.createPersonIdentifier(1,person,identifier(profile,number),key());
        long first=user(),second=user();var roster=context.getBean(PartyRosterService.class);
        var command=new cn.jualn.miniapp.module.person.bo.QualificationBO.Apply(name,number);
        var results=race(()->roster.apply(first,command,key()),()->roster.apply(second,command,key()));
        assertEquals(1,results.stream().filter(cn.jualn.miniapp.module.person.bo.QualificationBO.Resource.class::isInstance).count());
        assertEquals(1,results.stream().filter(ContractProblemException.class::isInstance).count());
        var current=bindings.getPersonAccountBinding(1,person).value().binding();long winner=Long.parseLong(current.userId());
        long bindingCount=count("person_account_binding");String sameKey=key();var same=roster.apply(winner,command,sameKey);
        assertEquals(current.bindingId(),same.value().bindingId());assertEquals(same,roster.apply(winner,command,sameKey));
        assertEquals(bindingCount,count("person_account_binding"));assertEquals(1,roster.applications(winner,1,20).totalItems());
        rosterProjection("PartyIdentityApplication",cn.jualn.miniapp.module.person.converter.QualificationConverter.application(same.value()));
        var scopes=context.getBean(PersonBusinessScopes.class);var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(status->{scopes.lock(false);assertEquals(person,scopes.currentPerson(winner));});
        assertEquals("UNKNOWN",roster.myIdentity(winner).identityVerification()); // No roster, organization or stage was required.
        bindings.endUserPersonBinding(1,current.bindingId(),new End("unlink","source"),key(),bindings.getUserPersonBinding(1,current.bindingId()).etag());
        tx.executeWithoutResult(status->{scopes.lock(false);assertNull(scopes.currentAccountBinding(winner));});
        String request=key();var again=roster.apply(winner,command,request);assertEquals(again,roster.apply(winner,command,request));
        assertNotEquals(current.bindingId(),again.value().bindingId());assertEquals(2,roster.applications(winner,1,20).totalItems());
        tx.executeWithoutResult(status->{scopes.lock(false);assertFalse(scopes.sameBindingCycle(winner,person,current.bindingId(),"missing","old",scopes.now()));});
    }
    @Test void rosterRevisionRepeatedImportsReuseFactsAndBlockNameOrSnapshotConflicts() throws Exception {
        var roster=context.getBean(PartyRosterService.class);var foundation=context.getBean(PersonFoundationService.class);
        context.getBean(PersonFoundationDatabaseHttpTest.TestCatalog.class).confirmed=true;
        String org=foundation.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("repeat","",null,"r","s","PARTY_BRANCH"),key()).value().organizationId();
        String number="00"+key();importRoster(roster,org,"Repeat Name",number,"Class");
        long profiles=count("person_profile"),members=count("person_membership");
        long facts=db.queryForObject("SELECT COUNT(*) FROM person_foundation_event WHERE aggregate_type='PERSON'",Long.class);
        var repeat=rosterRevisionPreview(roster,org,"Repeat Name",number,"Class");var repeatPlan=roster.rows(1,repeat.value().importId(),1,20);
        assertEquals("KEEP",repeatPlan.value().items().get(0).resolution().sourceAction());
        rosterProjection("PartyRosterPreviewRowPage",cn.jualn.miniapp.module.person.converter.QualificationConverter.preview(repeatPlan.value()));
        roster.commit(1,repeat.value().importId(),new cn.jualn.miniapp.module.person.bo.QualificationBO.Commit("r","s",true,repeatPlan.value().planId()),key(),repeat.etag());
        assertEquals(profiles,count("person_profile"));assertEquals(members,count("person_membership"));
        assertEquals(facts,db.queryForObject("SELECT COUNT(*) FROM person_foundation_event WHERE aggregate_type='PERSON'",Long.class));
        for(String[] values:new String[][]{{"Different Name","Class"},{"Repeat Name","Different Class"}}) {
            var mapped=rosterRevisionPreview(roster,org,values[0],number,values[1]);
            var preview=roster.rows(1,mapped.value().importId(),1,20);assertEquals("BLOCKED",preview.value().items().get(0).resolution().outcome());
            assertEquals(values[0].equals("Repeat Name")?"SOURCE_CONFLICT":"NAME_MISMATCH",preview.value().items().get(0).errors().get(0).reason());
            rosterProjection("PartyRosterPreviewRowPage",cn.jualn.miniapp.module.person.converter.QualificationConverter.preview(preview.value()));
            var failure=assertThrows(ContractProblemException.class,()->roster.commit(1,mapped.value().importId(),new cn.jualn.miniapp.module.person.bo.QualificationBO.Commit("r","s",true,preview.value().planId()),key(),mapped.etag()));
            assertEquals(HttpStatus.CONFLICT,failure.getStatus());assertEquals(profiles,count("person_profile"));
        }
        var entry=roster.entries(1,1,20,new cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery(org,null,null,null,null,null)).items().get(0);
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{var readers=context.getBean(PersonFactReaders.class);var c=readers.open(1);var identity=readers.identity(entry.personId(),c);
            assertEquals("ROSTER_IMPORT",identity.source());assertEquals("PARTY_ACTIVIST",identity.stageId());});
    }
    cn.jualn.miniapp.module.person.bo.QualificationBO.Resource<cn.jualn.miniapp.module.person.bo.QualificationBO.Import> rosterRevisionPreview(PartyRosterService roster,String organization,String name,String number,String major) throws Exception {
        byte[] bytes;try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("rows");var header=sheet.createRow(0);var row=sheet.createRow(1);
            var labels=List.of("name","number","class","stage");var values=List.of(name,number,major,"activist");
            for(int i=0;i<4;i++) {header.createCell(i).setCellValue(labels.get(i));row.createCell(i).setCellValue(values.get(i));}book.write(out);bytes=out.toByteArray();
        }
        var uploaded=roster.upload(1,organization,"synthetic source","source.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes,key());
        return roster.map(1,uploaded.value().importId(),new cn.jualn.miniapp.module.person.bo.QualificationBO.Mapping(0,1,new cn.jualn.miniapp.module.person.bo.QualificationBO.Columns(1,2,3,4),List.of(new cn.jualn.miniapp.module.person.bo.QualificationBO.StageValue("activist","PARTY_ACTIVIST"))),key(),uploaded.etag());
    }
    @Test void rosterRevisionCompletenessNeverRevivesAfterMembershipOrOrganizationRestoration() {
        var service=context.getBean(PersonFoundationService.class);String person=create("A7 member").value().personId();
        String org=service.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("A7","",null,"r","s","PARTY_BRANCH"),key()).value().organizationId();
        var unknown=service.rosterCompleteness(1,org);assertEquals("UNCONFIRMED",unknown.value().status());
        assertEquals(unknown.etag(),service.rosterCompleteness(1,org).etag());
        var command=new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("CONFIRM",unknown.value().membershipVersion(),"all checked","source");String request=key();
        var confirmed=service.changeRosterCompleteness(1,org,command,request,unknown.etag());assertEquals("COMPLETE",confirmed.value().status());
        var local=service.localMembership(1,person,org);
        service.changeLocalMembership(1,person,org,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.JOIN,"r","s"),key(),local.etag());
        local=service.localMembership(1,person,org);
        service.changeLocalMembership(1,person,org,new cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberChange(cn.jualn.miniapp.module.person.bo.PersonBO.LocalMemberKind.REMOVE,"r","s"),key(),local.etag());
        var invalid=service.rosterCompleteness(1,org);assertEquals("INVALIDATED",invalid.value().status());assertNotEquals(confirmed.value().membershipVersion(),invalid.value().membershipVersion());
        assertEquals(confirmed,service.changeRosterCompleteness(1,org,command,request,unknown.etag()));assertEquals("INVALIDATED",service.rosterCompleteness(1,org).value().status());
        confirmed=service.changeRosterCompleteness(1,org,new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("CONFIRM",invalid.value().membershipVersion(),"all checked again","source"),key(),invalid.etag());
        var organization=service.organization(1,org);
        service.updateOrganization(1,org,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange(null,null,"INACTIVE","r","s",null),key(),organization.etag());
        organization=service.organization(1,org);service.updateOrganization(1,org,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange(null,null,"ACTIVE","r","s",null),key(),organization.etag());
        invalid=service.rosterCompleteness(1,org);assertEquals("INVALIDATED",invalid.value().status());
        assertEquals(List.of("INVALIDATE","CONFIRM","INVALIDATE","CONFIRM"),service.rosterCompletenessHistory(1,org,1,20).items().stream().map(cn.jualn.miniapp.module.person.bo.PersonBO.RosterEvent::action).toList());
        var revoked=service.changeRosterCompleteness(1,org,new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("REVOKE",invalid.value().membershipVersion(),"withdraw","s"),key(),invalid.etag());assertEquals("REVOKED",revoked.value().status());
    }
    @Test void rosterRevisionPurgedFilesKeepFormalFactsSourceAndReceipts() throws Exception {
        var roster=context.getBean(PartyRosterService.class);var service=context.getBean(PersonFoundationService.class);
        context.getBean(PersonFoundationDatabaseHttpTest.TestCatalog.class).confirmed=true;
        String org=service.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("purge","",null,"r","s","PARTY_BRANCH"),key()).value().organizationId();
        var mapped=rosterRevisionPreview(roster,org,"Retained",key(),"Class");String id=mapped.value().importId();
        var plan=roster.rows(1,id,1,20);String request=key();var command=new cn.jualn.miniapp.module.person.bo.QualificationBO.Commit("r","s",true,plan.value().planId());
        var committed=roster.commit(1,id,command,request,mapped.etag());long facts=count("person_foundation_event");
        db.update("UPDATE party_roster_import SET file_retain_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
        context.getBean(cn.jualn.miniapp.module.person.mapper.QualificationMapper.class).purgeFiles();
        assertEquals("PURGED",roster.getImport(1,id).value().fileStatus());
        rosterProjection("PartyRosterImport",cn.jualn.miniapp.module.person.converter.QualificationConverter.rosterImport(roster.getImport(1,id).value()));
        for(Runnable read:List.<Runnable>of(()->roster.rows(1,id,1,20),()->roster.sheets(1,id),()->roster.columns(1,id,0,1))) {
            var failure=assertThrows(ContractProblemException.class,read::run);assertEquals(HttpStatus.GONE,failure.getStatus());assertEquals("/problems/roster-file-unavailable",failure.getType());
        }
        var replay=roster.commit(1,id,command,request,mapped.etag());assertEquals(committed.value().version(),replay.value().version());
        assertEquals("PURGED",replay.value().fileStatus());assertEquals(roster.getImport(1,id).value().fileRetainUntil(),replay.value().fileRetainUntil());
        assertEquals(facts,count("person_foundation_event"));
        var entry=roster.entries(1,1,20,new cn.jualn.miniapp.module.person.bo.QualificationBO.EntryQuery(org,null,null,null,null,null)).items().get(0);
        assertEquals("TRACE_ONLY",entry.sourceEffect());
        rosterProjection("PartyRosterEntry",cn.jualn.miniapp.module.person.converter.QualificationConverter.entry(entry));assertEquals("VERIFIED",service.identity(1,entry.personId()).value().verification().name());assertEquals(1,roster.history(1,entry.rosterEntryId(),1,20).totalItems());
    }
    @Test void rosterRevisionCompletenessExpiresAndConflictingConfirmationsSerialize() throws Exception {
        var service=context.getBean(PersonFoundationService.class);String person=create("Expiring A7").value().personId();
        String organization=service.createOrganization(1,new cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationChange("expiry","",null,"r","s","PARTY_BRANCH"),key()).value().organizationId();
        var members=service.memberships(1,person);
        var until=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(3).withNano(0);
        service.replaceMemberships(1,person,new cn.jualn.miniapp.module.person.bo.PersonBO.MembershipChange(cn.jualn.miniapp.module.person.bo.PersonBO.Verification.VERIFIED,List.of(new cn.jualn.miniapp.module.person.bo.PersonBO.MemberInput(organization,until)),"r","s"),key(),members.etag());
        var before=service.rosterCompleteness(1,organization);
        var command=new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("CONFIRM",before.value().membershipVersion(),"all checked","s");
        var results=race(()->service.changeRosterCompleteness(1,organization,command,key(),before.etag()),()->service.changeRosterCompleteness(1,organization,command,key(),before.etag()));
        assertEquals(1,results.stream().filter(Result.class::isInstance).count());assertEquals(1,results.stream().filter(ContractProblemException.class::isInstance).count());
        var scopes=context.getBean(PersonBusinessScopes.class);var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(status->{scopes.lock(false);scopes.requireRoster(organization,scopes.now());});
        Thread.sleep(Math.max(1,java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),until.plusNanos(1000000)).toMillis()));
        tx.executeWithoutResult(status->{scopes.lock(false);assertThrows(ContractProblemException.class,()->scopes.requireRoster(organization,scopes.now()));status.setRollbackOnly();});
        var invalid=service.rosterCompleteness(1,organization);assertEquals("INVALIDATED",invalid.value().status());
        wire("GET","/v1/admin/organizations/"+organization+"/roster-completeness",null,null,null,"application/json",200);
        wire("GET","/v1/admin/organizations/"+organization+"/roster-completeness/history",null,null,null,"application/json",200);
        var confirmed=wire("POST","/v1/admin/organizations/"+organization+"/roster-completeness/changes",json.writeValueAsString(new cn.jualn.miniapp.module.person.bo.PersonBO.RosterChange("CONFIRM",invalid.value().membershipVersion(),"empty set checked","s")),key(),invalid.etag(),"application/json",200);
        assertEquals("COMPLETE",confirmed.body().path("status").asText());
        auth.denied.add("organization:read");assertThrows(ContractProblemException.class,()->service.rosterCompleteness(1,organization));auth.denied.clear();
    }
    @Test void rosterRevisionV87UpgradeRetainsCiphertextAndInvalidatesUnprovenCompleteness() throws Exception {
        String url=System.getProperty("managed.person.test.jdbcUrl")+"v87";String schema=url.substring(url.lastIndexOf('/')+1);
        var admin=new JdbcTemplate(source(url.substring(0,url.lastIndexOf('/'))+"/mysql"));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema));
        admin.execute("CREATE DATABASE "+schema);var legacy=source(url);
        Flyway.configure().dataSource(legacy).target("86").load().migrate();var data=new JdbcTemplate(legacy);
        String organization=key(),organizationEvent=key(),importEvent=key(),batch=key();
        data.update("INSERT INTO user_profile(id,openid) VALUES(1,'v87-upgrade')");
        data.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,'ORGANIZATION',?,1,'CREATE','{}',1,UTC_TIMESTAMP(6),'r','s','LOCAL_MANUAL',?)",organizationEvent,organization,key());
        data.update("INSERT INTO person_organization(id,name,description,status,revision,created_at,updated_at,last_change_id,type) VALUES(?,'legacy','','ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,'PARTY_BRANCH')",organization,organizationEvent);
        data.update("INSERT INTO person_roster_completeness(organization_id,verification,version,membership_digest,source_reference) VALUES(?,'VERIFIED',?,REPEAT('a',64),'legacy synthetic proof')",organization,key());
        data.update("INSERT INTO person_foundation_event(id,aggregate_type,aggregate_id,aggregate_version,operation,after_json,operator_user_id,recorded_at,reason,verification_reference,source,receipt_id) VALUES(?,'ROSTER_IMPORT',?,1,'createPartyRosterImport','{}',1,UTC_TIMESTAMP(6),'r','s','ADMIN_VERIFIED_SOURCE',?)",importEvent,batch,key());
        data.update("INSERT INTO party_roster_import(id,organization_id,creator,source_reference,file_fingerprint,workbook,columns_json,state,revision,expires_at,last_event_id) VALUES(?,?,1,'s',REPEAT('a',64),X'0102','[]','UPLOADED',1,UTC_TIMESTAMP(6)+INTERVAL 24 HOUR,?)",batch,organization,importEvent);
        var migration=Flyway.configure().dataSource(legacy).load();migration.migrate();migration.validate();
        assertEquals("UNCONFIRMED",data.queryForObject("SELECT status FROM person_roster_completeness WHERE organization_id=?",String.class,organization));
        assertEquals("UNKNOWN",data.queryForObject("SELECT verification FROM person_roster_completeness WHERE organization_id=?",String.class,organization));
        assertEquals(1,data.queryForObject("SELECT COUNT(*) FROM party_roster_import WHERE id=? AND workbook=X'0102' AND file_retain_until=expires_at+INTERVAL 24 HOUR",Integer.class,batch));
        assertEquals(2,data.queryForObject("SELECT COUNT(*) FROM person_foundation_event",Integer.class));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/person-roster-migration.json").toFile(),Map.of("migration","V86 to V87","schema",schema,"result","PASS"));
    }
    void rosterProjection(String schema,Object value) {
        rosterProjections.add(Map.of("schema",schema,"value",json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(value)));
    }
    Result<Profile> create(String name) { return people.createManagedPerson(1, new Create(name, null, "checked", "synthetic"), key()); }
    IdentifierCreate identifier(Result<Profile> p, String number) { return new IdentifierCreate("STUDENT_NUMBER", "SYNTHETIC_CAMPUS", number, "VERIFIED", "verified", "source", p.value().version()); }
    Bind bind(Result<Profile> p, long user) { return new Bind(p.value().personId(), Long.toString(user), p.value().version(), bindings.getPersonAccountBinding(1, p.value().personId()).value().version(), bindings.getAccountPersonBinding(1, user).value().version(), "checked", "source"); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    long user() { long id = userSequence++; addUser(id); return id; }
    void addUser(long id) { db.update("INSERT INTO user_profile(id,openid) VALUES(?,?)", id, "managed-test-" + id); db.update("INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at) VALUES(?,'ASSIGNMENT',?,UTC_TIMESTAMP(6))", id, key()); }
    static String key() { return UUID.randomUUID().toString(); }
    static String testKey() { byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b); return Base64.getEncoder().encodeToString(b); }
    record Wire(JsonNode body, String etag) {}
    Wire wire(String method, String path, String body, String key, String etag, String contentType, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType).header("X-Person-Test-Actor", "1");
        if (key != null) request.header("Idempotency-Key", key); if (etag != null) request.header("*".equals(etag)?"If-None-Match":"If-Match", etag);
        var response = HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body()); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        var node = json.readTree(response.body()); String version = response.headers().firstValue("ETag").orElse(null);
        evidence.add(Map.of("method", method, "path", path, "status", status, "body", node, "etag", version == null ? "" : version,
                "cacheControl", response.headers().firstValue("Cache-Control").orElse(""), "contentType", response.headers().firstValue("Content-Type").orElse(""), "location", response.headers().firstValue("Location").orElse("")));
        return new Wire(node, version);
    }
    List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            Callable<Object> x = () -> { gate.await(); try { return a.call(); } catch (Exception e) { return e; } };
            Callable<Object> y = () -> { gate.await(); try { return b.call(); } catch (Exception e) { return e; } };
            var first = pool.submit(x); var second = pool.submit(y); gate.countDown(); return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Configuration
    @Import({cn.jualn.miniapp.module.person.service.access.DutyCandidateDirectory.class,cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService.class,cn.jualn.miniapp.module.person.service.binding.PersonBindingTransitions.class,cn.jualn.miniapp.module.admin.operation.AdminAuditTestConfiguration.class,ManagedPersonService.class, ManagedPersonIdentifierService.class, ManagedPersonBindingService.class,
            ManagedPersonAccess.class, ManagedPersonJournal.class, PersonIdentifierNamespaces.class, ManagedPersonController.class})
    static class Config extends PersonFoundationDatabaseHttpTest.Config {
        @Override @Bean DataSource dataSource() {
            var delegate = source(System.getProperty("managed.person.test.jdbcUrl"));
            return new AbstractDataSource() {
                public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
                public Connection getConnection(String u, String p) throws SQLException { return wrap(delegate.getConnection(u, p)); }
                Connection wrap(Connection connection) {
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            var result = method.invoke(connection, args);
                            if (method.getName().equals("commit") && loseCommit.compareAndSet(true, false)) throw new SQLTransientConnectionException("Synthetic acknowledgement loss after real commit");
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
                }
            };
        }
        @Override @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setCacheEnabled(false); factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/PersonnelAccountMapper.xml"),new ClassPathResource("mapper/MentorWorkflowMapper.xml"),new ClassPathResource("mapper/DutyCandidateMapper.xml"),new ClassPathResource("mapper/PersonMapper.xml"), new ClassPathResource("mapper/ManagedPersonMapper.xml"), new ClassPathResource("mapper/MentorAnswerEvidence.xml"), new ClassPathResource("mapper/MentorQuestionMapper.xml"), new ClassPathResource("mapper/QualificationMapper.xml"), new ClassPathResource("mapper/RbacMapper.xml"), new ClassPathResource("mapper/RbacOfflineMapper.xml"), new ClassPathResource("mapper/UnifiedMapper.xml"), new ClassPathResource("mapper/PersonBusinessMapper.xml"));
            return factory.getObject();
        }
    }
}
