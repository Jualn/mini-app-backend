package cn.jualn.miniapp.module.person.service.bootstrap;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonProjection;

import cn.jualn.miniapp.module.person.bo.PersonBO.Action;
import cn.jualn.miniapp.module.person.bo.PersonBO.ActionSet;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.Scope;
import cn.jualn.miniapp.module.person.bo.PersonBO.Target;

import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.authorization.service.RbacInitialControlVerifier;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.actionKey;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.future;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.hash;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.reason;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.targetId;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.text;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.uuid;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.validate;

/** Explicit offline control preparation/execution. No Spring Bean, HTTP, DDL, role or User writer.
 * Exact approved plan bytes, server identity, epoch and authorization versions must still match.
 * Revoke is a separately approved plan; immutable facts and receipts are retained. */
public final class PersonInitialAuthority {
    public record Preparation(long actorUserId,long granteeUserId,Domain domain,List<Action> actions,Target target,
                              String validUntil,String executorReference,String approvalReference,String approvalSha256,
                              String reason,String revokeScopeGrantId) {}
    public record Plan(int version,String operationId,String jdbcUrl,String serverUuid,String database,long expectedEpoch,
                       long actorUserId,String actorAuthorizationVersion,long granteeUserId,String granteeAuthorizationVersion,
                       Domain domain,List<Action> actions,Target target,String validUntil,String executorReference,String approvalReference,
                       String approvalSha256,String reason,String planExpiresAt,String revokeScopeGrantId) {
        public Plan { actions=List.copyOf(actions); }
    }
    private final String url;
    private final JdbcTemplate jdbc;
    private final PersonMapper people;
    private final RbacInitialControlVerifier verifier;
    private final TransactionTemplate transaction;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public PersonInitialAuthority(String url,DataSource dataSource) throws Exception {
        if(url==null || !url.matches("jdbc:mysql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+")) throw new IllegalArgumentException("Explicit loopback operator tunnel and database required");
        this.url=url;jdbc=new JdbcTemplate(dataSource);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);
        config.addMapper(PersonMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(config);
        var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setBanner(false);factory.setGlobalConfig(global);
        factory.setMapperLocations(new ClassPathResource("mapper/PersonMapper.xml"));
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));people=session.getMapper(PersonMapper.class);
        verifier=new RbacInitialControlVerifier(dataSource);transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(30);
    }
    public Plan inspect(long actor,long grantee,Domain domain,List<Action> actions,Target target,String validUntil,String executor,String approval,String approvalSha,String reason,String revoke) {
        return transaction.execute(tx->{
            long epoch=people.gateShared();lock(actor,grantee,target);
            var now=people.now();var root=verifier.requireRoot(actor,now);String receiver=verifier.requireAssignmentAccount(grantee,now);
            var p=new Plan(1,uuid(),url,server(),database(),epoch,actor,root.authorizationVersion(),grantee,receiver,domain,actions,target,validUntil,executor,approval,approvalSha,reason,now.plusMinutes(30).atOffset(ZoneOffset.UTC).toString(),revoke);
            validate(p,now);return p;
        });
    }
    public String verify(Plan p) { return transaction.execute(tx->{people.gateShared();lock(p.actorUserId(),p.granteeUserId(),p.target());verifyCurrent(p,false);return "VALIDATED_NOT_EXECUTED";}); }
    public Scope execute(Plan p,String approvedPlanSha256,byte[] originalPlanBytes) {
        if(!hashBytes(originalPlanBytes).equals(approvedPlanSha256) || !p.equals(read(originalPlanBytes,Plan.class))) throw new IllegalArgumentException("Exact approved plan bytes required");
        return transaction.execute(tx->{
            long epoch=people.gateExclusive();lock(p.actorUserId(),p.granteeUserId(),p.target());var now=people.now();
            var prior=people.receipt(p.actorUserId(),"personAuthorityBootstrap",p.operationId(),p.operationId());
            // Current ROOT, actual account and source proofs remain mandatory on recovery.
            verifyCurrent(p,prior!=null);
            if(prior!=null) {
                if(!approvedPlanSha256.equals(prior.getFingerprint())) throw new IllegalArgumentException("Approved operation differs");
                return read(prior.getResultJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),Scope.class);
            }
            var row=new PersonRows.Scope();Scope before=null;
            if(p.revokeScopeGrantId()!=null) {
                row=people.scope(p.revokeScopeGrantId());
                if(row==null || !"ADMINISTER".equals(row.getMode()) || row.getGranteeUserId()!=p.granteeUserId()
                        || !row.getDomain().equals(p.domain().name()) || !row.getActions().equals(actionKey(new ActionSet(p.domain(),p.actions()))) || !PersonProjection.target(row).equals(p.target()) || !PersonFactReaders.live(row,now)) throw new IllegalArgumentException("Control revoke target differs");
                before=PersonProjection.scope(row,now);row.setRevision(Math.addExact(row.getRevision(),1));row.setRevokedAt(now);row.setRevokedBy(p.actorUserId());row.setRevokeReason(p.reason());row.setCurrentSlot(null);
            } else {
                people.releaseExpiredScopes(now);row.setId(uuid());row.setGranteeUserId(p.granteeUserId());row.setMode("ADMINISTER");row.setDomain(p.domain().name());row.setActions(actionKey(new ActionSet(p.domain(),p.actions())));row.setTargetType(p.target().type());row.setTargetId("DIRECTORY".equals(p.target().type())?"":targetId(p.target()));
                row.setValidFrom(now);row.setValidUntil(parse(p.validUntil()));row.setGrantedBy(p.actorUserId());row.setReason(p.reason());row.setVerificationReference(p.approvalReference());row.setSource("APPROVED_BOOTSTRAP");row.setRevision(1);row.setCurrentSlot(1);
            }
            String eventId=uuid(),receiptId=uuid();row.setLastEventId(eventId);var after=PersonProjection.scope(row,now);
            var e=new PersonRows.Event();e.setId(eventId);e.setAggregateType("SCOPE");e.setAggregateId(row.getId());e.setAggregateVersion(row.getRevision());e.setOperation(p.revokeScopeGrantId()==null?"BOOTSTRAP_GRANT":"BOOTSTRAP_REVOKE");
            // Approved plan, including current owner versions and evidence digest, is durable audit input.
            e.setBeforeJson(encode(new BootstrapAudit(p,before)));e.setAfterJson(encode(after));e.setOperatorUserId(p.actorUserId());e.setRecordedAt(now);e.setReason(p.reason());e.setVerificationReference(p.approvalReference());e.setSource("APPROVED_BOOTSTRAP");e.setReceiptId(receiptId);one(people.insertEvent(e));
            var r=new PersonRows.Receipt();r.setId(receiptId);r.setActor(p.actorUserId());r.setOperation("personAuthorityBootstrap");r.setTarget(p.operationId());r.setCommandKey(p.operationId());r.setFingerprint(approvedPlanSha256);r.setResultJson(encode(after));r.setEtag("\""+hash(encode(after))+"\"");r.setStatus(200);r.setEventId(eventId);r.setCreatedAt(now);one(people.insertReceipt(r));
            if(p.revokeScopeGrantId()==null) one(people.insertScope(row));else one(people.revokeScope(row));
            one(people.advanceEpoch(epoch));verifier.requireRoot(p.actorUserId(),people.now());future(parse(p.validUntil()),people.now());return after;
        });
    }
    public record BootstrapAudit(Plan approvedPlan,Scope previous) {}
    private void verifyCurrent(Plan p,boolean replay) {
        var now=people.now();validate(p,now);
        if(!url.equals(p.jdbcUrl()) || !server().equals(p.serverUuid()) || !database().equals(p.database())) throw new IllegalArgumentException("Target changed");
        if(!verifier.requireRoot(p.actorUserId(),now).authorizationVersion().equals(p.actorAuthorizationVersion()) || !verifier.requireAssignmentAccount(p.granteeUserId(),now).equals(p.granteeAuthorizationVersion())) throw new IllegalArgumentException("Authorization changed");
        if(!replay && people.gateShared()!=p.expectedEpoch()) throw new IllegalArgumentException("Authority epoch changed; inspect again");
        if(people.corruptSource()!=0 || people.corruptScopes(p.granteeUserId())!=0) throw cn.jualn.miniapp.module.person.PersonProblems.unavailable();
    }
    private void validate(Plan p,LocalDateTime now) {
        if(p.version()!=1 || p.actorUserId()==p.granteeUserId() || p.actorUserId()<=0 || p.granteeUserId()<=0 || p.domain()==null || p.target()==null
                || p.actions().isEmpty() || p.actions().stream().distinct().count()!=p.actions().size() || p.actions().stream().anyMatch(a->!PersonFactReaders.supported(p.domain(),a))
                || !Set.of("PERSON","ORGANIZATION","DIRECTORY").contains(p.target().type()) || "DIRECTORY".equals(p.target().type()) && p.domain()!=Domain.ORGANIZATION
                || !OffsetDateTime.parse(p.planExpiresAt()).toInstant().isAfter(now.toInstant(ZoneOffset.UTC))) throw new IllegalArgumentException("Invalid or expired approved plan");
        text(p.executorReference(),128,"executorReference",false);text(p.approvalReference(),256,"approvalReference",false);text(p.reason(),2000,"reason",false);
        if(p.executorReference().equals(p.approvalReference()) || p.approvalSha256()==null || !p.approvalSha256().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Independent approval evidence required");
        if("PERSON".equals(p.target().type())) text(p.target().personId(),128,"personId",false);
        if("ORGANIZATION".equals(p.target().type()) && people.organization(p.target().organizationId())==null) throw new IllegalArgumentException("Organization missing");
        future(parse(p.validUntil()),now);
    }
    private void lock(long actor,long grantee,Target target) {
        var ids=new TreeSet<Long>(List.of(actor,grantee));
        verifier.lock(ids.stream().mapToLong(Long::longValue).toArray());
    }
    private String server() { return jdbc.queryForObject("SELECT @@server_uuid",String.class); }
    private String database() { return jdbc.queryForObject("SELECT DATABASE()",String.class); }
    private static LocalDateTime parse(String value) { if(value==null) return null;if(!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})")) throw new IllegalArgumentException("RFC3339 microseconds required");return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid approved data",e); } }
    private <T> T read(byte[] bytes,Class<T> type) { try { return json.readValue(bytes,type); } catch(Exception e) { throw new IllegalArgumentException("Invalid approved data",e); } }
    private static String hashBytes(byte[] bytes) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e) { throw new IllegalStateException(e); } }
    /** CLI only verifies unless exact --execute and approved digest are supplied. Credentials are not arguments. */
    public static void main(String[] args) throws Exception {
        if(args.length!=2 && args.length!=3 && args.length!=4) throw new IllegalArgumentException("<jdbc-url> inspect <preparation.json> OR <jdbc-url> <approved-plan.json> [--execute <approved-plan-sha256>]");
        String dbUser=System.getenv("PERSON_AUTHORITY_DB_USER"),password=System.getenv("PERSON_AUTHORITY_DB_PASSWORD");
        if(dbUser==null || password==null) throw new IllegalArgumentException("Explicit operator database credentials required");
        var tool=new PersonInitialAuthority(args[0],new DriverManagerDataSource(args[0]+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC",dbUser,password));
        if(args.length==3) {
            if(!"inspect".equals(args[1])) throw new IllegalArgumentException("Explicit inspect command required");
            var p=tool.read(Files.readAllBytes(Path.of(args[2])),Preparation.class);
            System.out.println(tool.encode(tool.inspect(p.actorUserId(),p.granteeUserId(),p.domain(),p.actions(),p.target(),p.validUntil(),p.executorReference(),p.approvalReference(),p.approvalSha256(),p.reason(),p.revokeScopeGrantId())));return;
        }
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));Plan plan=tool.read(bytes,Plan.class);
        if(args.length==2) System.out.println(tool.verify(plan));
        else { if(!"--execute".equals(args[2])) throw new IllegalArgumentException("Explicit execution flag required");System.out.println(tool.encode(tool.execute(plan,args[3],bytes))); }
    }
}
