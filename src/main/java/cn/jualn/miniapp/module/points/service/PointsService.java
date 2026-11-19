package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO;
import cn.jualn.miniapp.module.points.PointsProblems.Dependency;
import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptanceStatistics;
import cn.jualn.miniapp.module.points.bo.PointsBO.Accounting;
import cn.jualn.miniapp.module.points.bo.PointsBO.Adjustment;
import cn.jualn.miniapp.module.points.bo.PointsBO.Correction;
import cn.jualn.miniapp.module.points.bo.PointsBO.Reversal;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptingOrganizations;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.Create;
import cn.jualn.miniapp.module.points.bo.PointsBO.Eligibility;
import cn.jualn.miniapp.module.points.bo.PointsBO.Kind;
import cn.jualn.miniapp.module.points.bo.PointsBO.Ledger;
import cn.jualn.miniapp.module.points.bo.PointsBO.OrganizationStatistics;
import cn.jualn.miniapp.module.points.bo.PointsBO.Page;
import cn.jualn.miniapp.module.points.bo.PointsBO.PersonStatistics;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.bo.PointsBO.RequestScope;
import cn.jualn.miniapp.module.points.bo.PointsBO.Review;
import cn.jualn.miniapp.module.points.bo.PointsBO.StatisticsPage;
import cn.jualn.miniapp.module.points.bo.PointsBO.Status;
import cn.jualn.miniapp.module.points.bo.PointsBO.Summary;
import cn.jualn.miniapp.module.points.bo.PointsBO.Totals;
import cn.jualn.miniapp.module.points.entity.PointApplication;
import cn.jualn.miniapp.module.points.entity.PointLedger;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.module.points.mapper.PointsR3Mapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.LongStream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.jualn.miniapp.module.points.PointsProblems.conflict;
import static cn.jualn.miniapp.module.points.PointsProblems.forbidden;
import static cn.jualn.miniapp.module.points.PointsProblems.invalid;
import static cn.jualn.miniapp.module.points.PointsProblems.invalidParameter;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;
import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;

/**
 * Owns the transaction, lock order and immutable accounting facts. No remote side effects.
 */
@Service
public class PointsService {
    @Autowired
    private AdminAuditJournal managementAudit;
    private final PointsMapper db;
    private final PointsDependencies dependencies;
    private final ObjectMapper json;
    private final Clock clock;
    private final PointsPrivateMaterials materials;
    private PointsR3Mapper queries;

    @Autowired
    void queries(PointsR3Mapper queries) {
        this.queries = queries;
    }

    private TransactionTemplate transaction;
    private TransactionTemplate targetLookup;

    private void configureTargetLookup(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        targetLookup = new TransactionTemplate(manager);
        targetLookup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        targetLookup.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        targetLookup.setReadOnly(true);
    }

    // Resolve immutable beneficiary metadata in a separate, unauthorizing read. The business transaction
    // has no snapshot or application lock until its person gate and ordered owner locks are held.
    private PointApplication targetApplication(long id) {
        return targetLookup.execute(status -> requiredApplication(id));
    }

    private PointLedger targetLedger(long id) {
        return targetLookup.execute(status -> {
            var row = db.ledger(id);
            if (row == null) throw missing();
            return row;
        });
    }

    public PointsService(PointsMapper db,
                         PointsDependencies dependencies,
                         @Qualifier("applicationClock") Clock clock,
                         PointsPrivateMaterials materials,
                         PlatformTransactionManager manager) {
        this.db = db;
        this.dependencies = dependencies;
        this.clock = clock;
        this.materials = materials;
        configureTargetLookup(manager);
        // Durable receipts preserve microseconds and numeric values independently of legacy HTTP formatting.
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Eligibility eligibility() {
        outsideTransaction();
        long actor = dependencies.actor(Action.ELIGIBILITY);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.ELIGIBILITY, actor);
            return dependencies.eligibility(actor, dependencies.evaluatedAt(now()));
        });
    }

    public Application create(Create command, String key) {
        outsideTransaction();
        long actor = dependencies.actor(Action.CREATE);
        validate(command);
        validateKey(key);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.CREATE, actor);
            lockSubjects(dependencies.subject(actor).personId());
            dependencies.actor(Action.CREATE);
            var scope = new RequestScope(actor, "USER", "createPointApplication", 0, key);
            Application replay = replay(scope, command, Application.class);
            if (replay != null) {
                requireOwn(actor, replay);
                dependencies.finish(Action.CREATE, actor, actor, false);
                return replay;
            }
            var basis = dependencies.acceptance(actor, command.acceptingOrganizationId());
            var subject = dependencies.subject(actor);
            var row = new PointApplication();
            row.setPersonId(subject.personId());
            row.setSubmittedBindingId(subject.bindingId());
            row.setApplicantUserId(actor);
            row.setTitle(command.title());
            row.setDescription(command.description());
            row.setFileIds(write(command.fileIds()));
            row.setStatus("PENDING");
            row.setCreatedAt(now());
            row.setAcceptingOrganizationId(basis.organizationId());
            row.setMembershipId(basis.membershipId());
            db.insertApplication(row);
            dependencies.bindFiles(actor, row.getId(), command.fileIds());
            var result = application(requiredApplication(row.getId()));
            dependencies.finishApplication(Action.CREATE, actor, result, true);
            dependencies.recheckAcceptance(result);
            dependencies.audit(Action.CREATE, actor, actor, result.applicationId(), null, key);
            save(scope, command, result);
            return result;
        });
    }

    public AcceptingOrganizations acceptingOrganizations() {
        outsideTransaction();
        long actor = dependencies.actor(Action.ELIGIBILITY);
        return transaction.execute(status -> {
            dependencies.begin(Action.ELIGIBILITY, actor);
            return dependencies.acceptingOrganizations(actor);
        });
    }

    public Application close(long applicationId, String reason, String key) {
        outsideTransaction();
        long actor = dependencies.actor(Action.CLOSE);
        text(reason, 2000, "reason");
        validateKey(key);
        return transaction.execute(status -> {
            dependencies.begin(Action.CLOSE, actor);
            lockSubjects(ownSubject(actor).personId());
            var row = db.lockApplication(applicationId);
            if (row == null) throw missing();
            requireOwn(actor, application(row));
            var scope = new RequestScope(actor, "USER", "closeMyPointApplication", applicationId, key);
            var prior = replay(scope, reason, Application.class);
            if (prior != null) {
                dependencies.finish(Action.CLOSE, actor, actor, false);
                return prior;
            }
            if (!"PENDING".equals(row.getStatus())) throw conflict("application-finalized");
            row.setStatus("CLOSED");
            row.setClosedAt(now());
            row.setClosedByUserId(actor);
            row.setClosureReason(reason);
            if (db.closeApplication(row) != 1) throw conflict("application-finalized");
            var result = application(requiredApplication(row.getId()));
            dependencies.finish(Action.CLOSE, actor, actor, false);
            dependencies.audit(Action.CLOSE, actor, row.getApplicantUserId(), applicationId, null, key);
            save(scope, reason, result);
            return result;
        });
    }

    public Application review(long applicationId, Review command, String key) {
        outsideTransaction();
        long actor = dependencies.actor(Action.REVIEW);
        validate(command);
        validateKey(key);
        var first = targetApplication(applicationId);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.REVIEW, actor, first.getApplicantUserId());
            dependencies.requireApplication(actor, application(first), Action.REVIEW);
            rejectSelf(actor, first.getApplicantUserId(), first.getPersonId());
            lockSubjects(first.getPersonId());
            var row = db.lockApplication(applicationId);
            dependencies.actor(Action.REVIEW);
            dependencies.requireApplication(actor, application(row), Action.REVIEW);
            var scope = new RequestScope(actor, "ADMIN", "reviewPointApplication", applicationId, key);
            managementAudit.confirmed(actor, "reviewPointApplication", "POINT_APPLICATION", Long.toString(applicationId));
            Application replay = replay(scope, command, Application.class);
            if (replay != null) {
                dependencies.finishApplication(Action.REVIEW, actor, application(row), false);
                return replay;
            }
            if (!"PENDING".equals(row.getStatus())) throw conflict("application-finalized");
            row.setReviewerUserId(actor);
            row.setReviewedAt(now());
            row.setReviewNote(command.reviewNote());
            if (command.decision() == Status.APPROVED) {
                requireEligible(row.getPersonId());
                dependencies.requireFiles(application(row));
                PointsAmounts.adding(row.getPersonId(), db.lockedTotals(row.getPersonId()), command.awardedPoints(), now());
                var grant = entry(row, actor, Kind.GRANT, command.awardedPoints(), command.reviewNote(), null);
                db.insertLedger(grant);
                row.setGrantLedgerId(grant.getId());
                row.setAwardedPoints(command.awardedPoints());
            }
            row.setStatus(command.decision().name());
            if (db.finishApplication(row) != 1) throw conflict("application-finalized");
            var result = application(requiredApplication(row.getId()));
            dependencies.acceptResult(result, null);
            dependencies.finishApplication(Action.REVIEW, actor, result, command.decision() == Status.APPROVED);
            dependencies.recheckAcceptance(result);
            dependencies.audit(Action.REVIEW, actor, row.getApplicantUserId(), row.getId(), row.getGrantLedgerId(), key);
            save(scope, command, result);
            return result;
        });
    }

    public Ledger reverse(long ledgerId, Reversal command, String key) {
        return (Ledger) adjust(ledgerId, command.reason(), command.basis(), null, key);
    }

    public Correction correct(long ledgerId, Adjustment command, String key) {
        if (command.correctedPoints() < 1 || command.correctedPoints() > 10) throw invalid("correctedPoints");
        return (Correction) adjust(ledgerId, command.reason(), command.basis(), command.correctedPoints(), key);
    }

    private Object adjust(long ledgerId, String reason, String basis, Integer points, String key) {
        outsideTransaction();
        var action = points == null ? Action.REVERSE : Action.CORRECT;
        long actor = dependencies.actor(action);
        text(reason, 2000, "reason");
        text(basis, 2000, "basis");
        validateKey(key);
        var original = targetLedger(ledgerId);
        var first = targetApplication(original.getApplicationId());
        var command = points == null ? (Object) new Reversal(reason, basis) : new Adjustment(points, reason, basis);
        return transaction.execute(status -> {
            dependencies.begin(action, actor);
            dependencies.requireApplication(actor, application(first), action);
            rejectSelf(actor, original.getApplicantUserId(), original.getPersonId());
            lockSubjects(original.getPersonId());
            var app = db.lockApplication(original.getApplicationId());
            dependencies.actor(action);
            dependencies.requireApplication(actor, application(app), action);
            rejectSelf(actor, original.getApplicantUserId(), original.getPersonId());
            var scope = new RequestScope(actor, "ADMIN", points == null ? "reversePointGrant" : "correctPointGrant", ledgerId, key);
            managementAudit.confirmed(actor, scope.operation(), "POINT_LEDGER", Long.toString(ledgerId));
            Object prior = points == null ? replay(scope, command, Ledger.class) : replay(scope, command, Correction.class);
            if (prior != null) {
                dependencies.finishApplication(action, actor, application(app), false);
                return prior;
            }
            if (!java.util.Set.of("GRANT", "CORRECTION_GRANT").contains(original.getKind()))
                throw conflict("reversal-target-invalid");
            if (db.reversal(ledgerId) != null) throw conflict("grant-already-reversed");
            if (!"APPROVED".equals(app.getStatus())
                    || !original.getPersonId().equals(app.getPersonId()) || !original.getSubmittedBindingId().equals(app.getSubmittedBindingId())
                    || !original.getApplicantUserId().equals(app.getApplicantUserId())
                    || !original.getAcceptingOrganizationId().equals(app.getAcceptingOrganizationId()) || !original.getMembershipId().equals(app.getMembershipId()))
                throw unavailable(Dependency.AUTHORIZATION);
            if (points != null && points == original.getPointsDelta()) throw conflict("correction-unchanged");
            PointsAmounts.adding(original.getPersonId(), db.lockedTotals(original.getPersonId()), -original.getPointsDelta(), now());
            String correctionId = points == null ? null : java.util.UUID.randomUUID().toString();
            var reversal = entry(app, actor, Kind.REVERSAL, -original.getPointsDelta(), reason, ledgerId);
            reversal.setBasis(basis);
            reversal.setCorrectionId(correctionId);
            db.insertLedger(reversal);
            Object result;
            if (points == null) {
                result = ledger(reversal);
                dependencies.acceptResult(application(requiredApplication(app.getId())), (Ledger) result);
            } else {
                PointsAmounts.adding(original.getPersonId(), db.lockedTotals(original.getPersonId()), points, now());
                var replacement = entry(app, actor, Kind.CORRECTION_GRANT, points, reason, ledgerId);
                replacement.setCreatedAt(reversal.getCreatedAt());
                replacement.setBasis(basis);
                replacement.setCorrectionId(correctionId);
                db.insertLedger(replacement);
                var correction = new Correction(correctionId, app.getId(), app.getPersonId(), ledgerId, actor, reason, basis, reversal.getCreatedAt(), ledger(reversal), ledger(replacement));
                dependencies.acceptCorrection(application(requiredApplication(app.getId())), correction);
                result = correction;
            }
            dependencies.finishApplication(action, actor, application(app), false);
            dependencies.audit(action, actor, original.getApplicantUserId(), app.getId(), reversal.getId(), key);
            save(scope, command, result);
            return result;
        });
    }

    public Correction correction(String correctionId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        return transaction.execute(status -> {
            dependencies.begin(Action.READ, actor);
            var rows = db.correctionLedgers(correctionId);
            if (rows.isEmpty()) throw missing();
            if (rows.size() != 2) throw unavailable(Dependency.AUTHORIZATION);
            var r = rows.stream().filter(l -> "REVERSAL".equals(l.getKind())).findFirst().orElseThrow(() -> unavailable(Dependency.AUTHORIZATION));
            var g = rows.stream().filter(l -> "CORRECTION_GRANT".equals(l.getKind())).findFirst().orElseThrow(() -> unavailable(Dependency.AUTHORIZATION));
            if (!java.util.Objects.equals(r.getApplicationId(), g.getApplicationId()) || !java.util.Objects.equals(r.getPersonId(), g.getPersonId())
                    || !java.util.Objects.equals(r.getOriginalLedgerId(), g.getOriginalLedgerId()) || !java.util.Objects.equals(r.getOperatorUserId(), g.getOperatorUserId())
                    || !java.util.Objects.equals(r.getSubmittedBindingId(), g.getSubmittedBindingId()) || !java.util.Objects.equals(r.getAcceptingOrganizationId(), g.getAcceptingOrganizationId())
                    || !java.util.Objects.equals(r.getMembershipId(), g.getMembershipId()) || !java.util.Objects.equals(r.getApplicantUserId(), g.getApplicantUserId())
                    || !java.util.Objects.equals(r.getReason(), g.getReason()) || !java.util.Objects.equals(r.getBasis(), g.getBasis()) || !java.util.Objects.equals(r.getCreatedAt(), g.getCreatedAt()))
                throw unavailable(Dependency.AUTHORIZATION);
            dependencies.requireApplication(actor, application(requiredApplication(r.getApplicationId())), Action.READ);
            return new Correction(correctionId, r.getApplicationId(), r.getPersonId(), r.getOriginalLedgerId(), r.getOperatorUserId(), r.getReason(), r.getBasis(), r.getCreatedAt(), ledger(r), ledger(g));
        });
    }

    public Page<Ledger> applicationLedger(long applicationId, Query q) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        validate(q);
        return transaction.execute(status -> {
            dependencies.begin(Action.READ, actor);
            var app = requiredApplication(applicationId);
            dependencies.requireApplication(actor, application(app), Action.READ);
            var filter = new Query(q.page(), q.pageSize(), null, q.kind(), applicationId);
            return new Page<>(db.ledgers(app.getPersonId(), filter).stream().map(this::ledger).toList(), q.page(), q.pageSize(), checkedCount(db.ledgerCount(app.getPersonId(), filter)));
        });
    }

    public Application mine(long applicationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.MY_READ, actor);
            var row = requiredApplication(applicationId);
            requireOwn(actor, application(row));
            return application(row);
        });
    }

    public Application adminApplication(long applicationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.READ, actor);
            var row = requiredApplication(applicationId);
            dependencies.requireApplication(actor, application(row), Action.READ);
            return application(row);
        });
    }

    public Ledger adminLedger(long ledgerId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.READ, actor);
            var row = db.ledger(ledgerId);
            if (row == null) throw missing();
            dependencies.requireApplication(actor, application(requiredApplication(row.getApplicationId())), Action.READ);
            return ledger(row);
        });
    }

    public Summary summary() {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.MY_READ, actor);
            var person = ownSubject(actor).personId();
            return PointsAmounts.summary(person, db.totals(person), dependencies.evaluatedAt(now()));
        });
    }

    public Page<Application> applications(Query q) {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        validate(q);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.MY_READ, actor);
            long count = checkedCount(db.applicationCount(ownSubject(actor).personId(), q));
            return new Page<>(db.applications(ownSubject(actor).personId(), q).stream().map(this::application).toList(), q.page(), q.pageSize(), count);
        });
    }

    public Page<Ledger> ledgers(Query q) {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        validate(q);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.MY_READ, actor);
            if (q.applicationId() != null) requireOwn(actor, application(requiredApplication(q.applicationId())));
            long count = checkedCount(db.ledgerCount(ownSubject(actor).personId(), q));
            return new Page<>(db.ledgers(ownSubject(actor).personId(), q).stream().map(this::ledger).toList(), q.page(), q.pageSize(), count);
        });
    }

    public Page<Application> adminApplications(Query q, String personId, String organizationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        validate(q);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.READ, actor);
            var plan = dependencies.applicationPlan(actor, personId, organizationId, q);
            long count = checkedCount(queries.applicationCount(plan, q));
            return new Page<>(queries.applications(plan, q).stream().map(this::application).toList(), q.page(), q.pageSize(), count);
        });
    }

    public StatisticsPage<PersonStatistics> personStatistics(Query q, String personId, String organizationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        validate(q);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.READ, actor);
            var plan = dependencies.personPlan(actor, personId, organizationId);
            long count = checkedCount(queries.participantCount(plan));
            var items = queries.personStatistics(plan, q).stream().map(row -> {
                var totals = PointsAmounts.summary(row.personId(), new Totals(row.granted(), row.reversed()), plan.now());
                return new PersonStatistics(row.personId(), totals.balance(), totals.totalGranted(), totals.totalReversed(),
                        checkedCount(row.applicationCount()), checkedCount(row.pendingCount()), checkedCount(row.approvedCount()), checkedCount(row.rejectedCount()), checkedCount(row.closedCount()));
            }).toList();
            return new StatisticsPage<>(items, q.page(), q.pageSize(), count, "CURRENT", plan.now());
        });
    }

    public StatisticsPage<OrganizationStatistics> organizationStatistics(Query q, String organizationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        validate(q);
        return transaction.execute(transactionStatus -> {
            dependencies.begin(Action.READ, actor);
            var plan = dependencies.statisticsPlan(actor, organizationId, false);
            long count = checkedCount(queries.currentOrganizationCount(plan));
            var items = queries.currentStatistics(plan, q).stream().map(row -> {
                var totals = PointsAmounts.summary(null, new Totals(row.granted(), row.reversed()), plan.now());
                return new OrganizationStatistics(row.organizationId(), checkedCount(row.participantCount()), totals.balance(), totals.totalGranted(), totals.totalReversed());
            }).toList();
            return new StatisticsPage<>(items, q.page(), q.pageSize(), count, "CURRENT", plan.now());
        });
    }

    public StatisticsPage<AcceptanceStatistics> acceptanceStatistics(Query q, String organizationId) {
        outsideTransaction();
        long actor = dependencies.actor(Action.READ);
        validate(q);
        return transaction.execute(status -> {
            dependencies.begin(Action.READ, actor);
            var plan = dependencies.statisticsPlan(actor, organizationId, true);
            var items = queries.acceptanceStatistics(plan, q).stream().map(row -> {
                var totals = PointsAmounts.summary(null, new Totals(row.granted(), row.reversed()), plan.now());
                return new AcceptanceStatistics(row.organizationId(), checkedCount(row.applicationCount()), totals.balance(), totals.totalGranted(), totals.totalReversed());
            }).toList();
            return new StatisticsPage<>(items, q.page(), q.pageSize(), checkedCount(plan.organizations().size()), "ACCEPTING_ORGANIZATION", plan.now());
        });
    }

    public PointMaterialBO.File myFile(String file) {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        return transaction.execute(status -> {
            dependencies.begin(Action.MY_READ, actor);
            var value = materials.candidate(file);
            if (value.applicationId() == null) {
                var draft = materials.read(actor, file);
                dependencies.finish(Action.MY_READ, actor, actor, false);
                return draft;
            }
            var application = application(requiredApplication(value.applicationId()));
            requireOwn(actor, application);
            if (!application.fileIds().contains(file)) throw missing();
            dependencies.finishApplication(Action.MY_READ, actor, application, false);
            return value;
        });
    }

    public PointMaterialBO.Access myFileAccess(String file) {
        outsideTransaction();
        long actor = dependencies.actor(Action.MY_READ);
        return transaction.execute(status -> {
            dependencies.begin(Action.MY_READ, actor);
            var value = materials.candidate(file);
            if (value.applicationId() == null) {
                var access = materials.draftAccess(actor, file);
                dependencies.finish(Action.MY_READ, actor, actor, false);
                return access;
            }
            var application = application(requiredApplication(value.applicationId()));
            requireOwn(actor, application);
            if (!application.fileIds().contains(file)) throw missing();
            var access = materials.reviewAccess(actor, file, application.applicationId(), application.applicantUserId());
            dependencies.finishApplication(Action.MY_READ, actor, application, false);
            return access;
        });
    }

    /**
     * File association, authorization, signature and audit remain in the same owner transaction.
     */
    public PointMaterialBO.Access reviewFileAccess(long actor, String file, long applicationId, long owner) {
        outsideTransaction();
        if (dependencies.actor(Action.MATERIAL_READ) != actor) throw missing();
        var target = targetApplication(applicationId);
        return transaction.execute(status -> {
            dependencies.begin(Action.MATERIAL_READ, actor, target.getApplicantUserId());
            dependencies.requireApplication(actor, application(target), Action.MATERIAL_READ);
            var current = application(requiredApplication(applicationId));
            if (current.applicantUserId() != owner || !current.fileIds().contains(file)) throw missing();
            var access = materials.reviewAccess(actor, file, applicationId, owner);
            dependencies.finishApplication(Action.MATERIAL_READ, actor, current, false);
            return access;
        });
    }

    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw unavailable(Dependency.AUTHORIZATION);
    }

    private PointApplication requiredApplication(long id) {
        var row = db.application(id);
        if (row == null) throw missing();
        return row;
    }

    private void requireEligible(String person) {
        if (!dependencies.approvalEligiblePerson(person)) throw forbidden("ineligible");
    }

    private PointsBO.Subject ownSubject(long actor) {
        try {
            return dependencies.subject(actor);
        } catch (ContractProblemException failure) {
            if (failure.getStatus() == HttpStatus.FORBIDDEN) throw missing();
            throw failure;
        }
    }

    private void requireOwn(long actor, Application application) {
        if (!ownSubject(actor).personId().equals(application.personId())) throw missing();
    }

    private void rejectSelf(long actor, long submittedUser, String person) {
        if (actor == submittedUser || dependencies.self(actor, person)) throw forbidden("self-review-forbidden");
    }

    private void lockSubjects(String... people) {
        Arrays.stream(people).distinct().sorted().forEach(person -> {
            db.ensureSubject(person);
            db.lockSubject(person);
        });
    }

    private PointLedger entry(PointApplication app, long actor, Kind kind, int delta, String reason, Long original) {
        var row = new PointLedger();
        row.setPersonId(app.getPersonId());
        row.setSubmittedBindingId(app.getSubmittedBindingId());
        row.setApplicantUserId(app.getApplicantUserId());
        row.setApplicationId(app.getId());
        row.setKind(kind.name());
        row.setPointsDelta(delta);
        row.setOperatorUserId(actor);
        row.setReason(reason);
        row.setOriginalLedgerId(original);
        row.setCreatedAt(now());
        row.setAcceptingOrganizationId(app.getAcceptingOrganizationId());
        row.setMembershipId(app.getMembershipId());
        row.setTraceId(org.slf4j.MDC.get(ObservabilityContext.TRACE_ID));
        return row;
    }

    private Application application(PointApplication row) {
        List<String> files;
        try {
            files = json.readValue(row.getFileIds(), new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid persisted Points files", failure);
        }
        return new Application(row.getId(), row.getApplicantUserId(), row.getTitle(), row.getDescription(), List.copyOf(files), Status.valueOf(row.getStatus()),
                row.getCreatedAt(), row.getReviewerUserId(), row.getReviewedAt(), row.getReviewNote(), row.getAwardedPoints(), row.getGrantLedgerId(),
                row.getAcceptingOrganizationId(), row.getMembershipId(), row.getClosedAt(), row.getClosedByUserId(), row.getClosureReason(), row.getPersonId(), row.getSubmittedBindingId(), row.getPersonName(), row.getAcceptingOrganizationName(),
                new Accounting(!"APPROVED".equals(row.getStatus()) ? "NONE" : row.getEffectiveLedgerId() == null ? "REVOKED" : "EFFECTIVE", row.getEffectivePoints() == null ? 0 : row.getEffectivePoints(), row.getEffectiveLedgerId()));
    }

    private Ledger ledger(PointLedger row) {
        return new Ledger(row.getId(), row.getApplicantUserId(), row.getApplicationId(), Kind.valueOf(row.getKind()), row.getPointsDelta(), row.getOperatorUserId(), row.getReason(), row.getOriginalLedgerId(), row.getCreatedAt(), row.getAcceptingOrganizationId(), row.getMembershipId(), row.getPersonId(), row.getSubmittedBindingId(), row.getCorrectionId(), row.getBasis());
    }

    private <T> T replay(RequestScope scope, Object payload, Class<T> type) {
        var receipt = db.receipt(scope);
        if (receipt == null) return null;
        if (!receipt.fingerprint().equals(hash(payload))) throw conflict("idempotency-conflict");
        try {
            return json.readValue(receipt.resultJson(), type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid persisted Points receipt", failure);
        }
    }

    private void save(RequestScope scope, Object payload, Object result) {
        db.saveReceipt(scope, hash(payload), write(result), now());
        if ("ADMIN".equals(scope.session()))
            managementAudit.operation(java.util.UUID.randomUUID().toString(), scope.actor(), scope.operation(), "reviewPointApplication".equals(scope.operation()) ? "POINT_APPLICATION" : "POINT_LEDGER", String.valueOf(scope.target()), now());
    }

    private String hash(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode Points fact", failure);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw invalidParameter("header", "Idempotency-Key");
    }

    public static void text(String value, int max, String field) {
        if (value == null || value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                || value.codePointCount(0, value.length()) > max) throw invalid(field);
    }

    public static void validate(Create command) {
        if (command.acceptingOrganizationId() == null || command.acceptingOrganizationId().isBlank() || command.acceptingOrganizationId().length() > 128)
            throw invalid("acceptingOrganizationId");
        text(command.title(), 120, "title");
        text(command.description(), 10000, "description");
        if (command.fileIds().isEmpty() || command.fileIds().size() > 9 || command.fileIds().stream().distinct().count() != command.fileIds().size())
            throw invalid("fileIds");
        command.fileIds().forEach(id -> {
            if (id == null || id.isEmpty() || id.length() > 128) throw invalid("fileIds");
        });
    }

    public static void validate(Review command) {
        if (command.reviewNote() != null) text(command.reviewNote(), 2000, "reviewNote");
        if (command.decision() == null || !java.util.Set.of(Status.APPROVED, Status.REJECTED).contains(command.decision()))
            throw invalid("decision");
        if (command.decision() == Status.APPROVED ? command.awardedPoints() == null || (command.awardedPoints() < 1 || command.awardedPoints() > 10) : command.awardedPoints() != null)
            throw invalid("awardedPoints");
    }

    public static void validate(Query q) {
        if (q.page() < 1 || q.pageSize() < 1 || q.pageSize() > 100) throw invalid("page");
    }

    private long checkedCount(long count) {
        if (count < 0 || count > PointsAmounts.MAX) throw conflict("overflow");
        return count;
    }
}
