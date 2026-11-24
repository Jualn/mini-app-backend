package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.access.PersonAuthentication;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Decision;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Page;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Receipt;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Row;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Setting;
import cn.jualn.miniapp.module.points.mapper.PointAcceptanceSettingsMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static cn.jualn.miniapp.module.points.PointsProblems.conflict;
import static cn.jualn.miniapp.module.points.PointsProblems.invalid;
import static cn.jualn.miniapp.module.points.PointsProblems.invalidParameter;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;
import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;
import cn.jualn.miniapp.module.points.PointsProblems.Dependency;

/** The Person authority gate serializes qualification changes with Points admission and approval. */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class PointAcceptanceSettingsService {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final PointAcceptanceSettingsMapper db;
    private final PersonBusinessScopes persons;
    private final PersonAuthentication authentication;
    private final TransactionalAuthorization accounts;
    private final UnifiedDutyReader duties;
    private final ObjectMapper json;
    private static final String PERMISSION = "points:acceptance-manage";

    private LocalDateTime open(long actor, boolean write) {
        accounts.begin(write?cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE:cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.SHARED,actor);
        var at = persons.now();
        authorized(actor, at);
        return at;
    }
    private void authorized(long actor, LocalDateTime at) {
        try {
            checkAuthorization(actor, at);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) throw failure;
            var problem = unavailable(Dependency.AUTHORIZATION);
            problem.initCause(failure);
            throw problem;
        }
    }
    private void checkAuthorization(long actor, LocalDateTime at) {
        authentication.require(actor, "POINTS", PERMISSION, at);
        var decision = accounts.current(actor, at);
        boolean owner = decision.ownerCapabilities().stream().anyMatch(c -> decision.owns(c.rootAssignmentId())
                && "POINTS".equals(c.namespace()) && c.permissionCodes().contains(PERMISSION));
        boolean delegated = duties.effective(actor, "POINTS_ACCEPTANCE_MANAGE", at).stream()
                .map(s -> duties.decode(s.selection(), Selection.class).target())
                .anyMatch(t -> "PLATFORM".equals(t.type()));
        if (!owner && !delegated) throw new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden", "Acceptance management duty required");
    }
    public Page list(long actor, int page, int size) {
        paging(page, size);var at = open(actor, false);
        return new Page(db.settings(size, (long)(page-1)*size).stream().map(this::project).toList(), page, size, db.count(), time(at));
    }
    public Setting get(long actor, String organization) {
        open(actor, false);return project(row(organization));
    }
    public Page history(long actor, String organization, int page, int size) {
        paging(page, size);var at = open(actor, false);row(organization);
        return new Page(db.history(organization, size, (long)(page-1)*size).stream().map(this::project).toList(), page, size,
                db.historyCount(organization), time(at));
    }
    public Setting decide(long actor, String organization, Decision command, String key, String match) {
        if (command == null || command.enabled() == null) throw invalid("enabled");
        if (command.reason() == null || command.reason().isBlank() || command.reason().codePointCount(0,command.reason().length()) > 2000) throw invalid("reason");
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw invalidParameter("header", "Idempotency-Key");
        if (match == null) throw ContractProblemException.preconditionRequired();
        if (!match.matches("\"(?:unconfigured|[0-9a-fA-F-]{36})\"")) throw invalidParameter("header", "If-Match");
        String expected = match.substring(1, match.length()-1);
        var at = open(actor, true);
        var prior = db.receipt(actor, organization, key);
        if (prior != null) {
            if (prior.enabled() != command.enabled() || !Objects.equals(prior.reason(), command.reason())
                    || !Objects.equals(prior.expectedVersion(), expected)) throw conflict("idempotency-conflict");
            return decode(prior.resultJson());
        }
        var current = project(row(organization));
        if (!current.version().equals(expected)) throw ContractProblemException.preconditionFailed();
        if (command.enabled() && !"ACTIVE".equals(current.organizationStatus())) throw conflict("acceptance-unavailable");
        String evidence = UUID.randomUUID().toString();
        var result = new Setting(organization, current.organizationName(), current.organizationStatus(),
                command.enabled() ? "ENABLED" : "DISABLED", evidence, command.enabled(), evidence,
                command.reason(), Long.toString(actor), time(at));
        var receipt = new Receipt(UUID.randomUUID().toString(), actor, organization, key, expected, command.enabled(),
                command.reason(), encode(result), evidence, at);
        one(db.insertEvidence(receipt));one(db.insertReceipt(receipt));
        managementAudit.operation(java.util.UUID.randomUUID().toString(),actor,"decidePointAcceptance","ORGANIZATION",String.valueOf(organization),at);
        int changed = db.pointToEvidence(receipt);if (changed != 1 && changed != 2) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                authorized(actor, persons.now());
                if (command.enabled() && !"ACTIVE".equals(row(organization).organizationStatus())) throw conflict("acceptance-unavailable");
            }
        });
        return result;
    }
    private Row row(String id) {var row = db.setting(id);if (row == null) throw missing();return row;}
    private Setting project(Row row) {
        boolean known = row.evidenceId() != null && row.organizationId().equals(row.evidenceOrganizationId())
                && "VERIFIED".equals(row.verification()) && row.enabled() != null;
        return new Setting(row.organizationId(), row.organizationName(), row.organizationStatus(),
                known ? row.enabled() ? "ENABLED" : "DISABLED" : "UNKNOWN",
                row.evidenceId() == null ? "unconfigured" : row.evidenceId(), known ? row.enabled() : null,
                row.evidenceId(), row.reason(), row.recordedBy() == null ? null : row.recordedBy().toString(), time(row.recordedAt()));
    }
    private static String time(LocalDateTime at) {return at == null ? null : at.toInstant(ZoneOffset.UTC).toString();}
    private static void paging(int page, int size) {if (page < 1 || size < 1 || size > 100) throw invalidParameter("query", "page");}
    private static void one(int count) {if (count != 1) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);}
    private String encode(Setting result) {try {return json.writeValueAsString(result);} catch (JsonProcessingException e) {throw unavailable(Dependency.BUSINESS_ELIGIBILITY);}}
    private Setting decode(String value) {try {return json.readValue(value, Setting.class);} catch (JsonProcessingException e) {throw unavailable(Dependency.BUSINESS_ELIGIBILITY);}}
}
