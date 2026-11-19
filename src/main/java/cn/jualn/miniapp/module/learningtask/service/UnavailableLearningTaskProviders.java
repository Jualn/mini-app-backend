package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;

/**
 * Formal authorization connected; unintegrated external capabilities continue to fail closed.
 */
@Component
public class UnavailableLearningTaskProviders implements LearningTaskProviders {
    @org.springframework.beans.factory.annotation.Autowired
    private LearningTaskScopePolicy scopes;
    private final ArticleReferenceReadService articles;
    private final LearningTaskAuthorizationPolicy authorization;
    private final LearningTaskTextSafety safety;
    private final cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService recipients;
    private final LearningTaskNotifications notifications;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.media.service.LearningMaterialService materials;

    public UnavailableLearningTaskProviders(ArticleReferenceReadService articles, LearningTaskAuthorizationPolicy authorization,
                                            LearningTaskTextSafety safety, cn.jualn.miniapp.module.user.service.UserLearningTaskRecipientService recipients,
                                            LearningTaskNotifications notifications) {
        this.articles = articles;
        this.authorization = authorization;
        this.safety = safety;
        this.recipients = recipients;
        this.notifications = notifications;
    }

    public ReadScope readableTasks() {
        long actor = authorization.authorize(Action.READ, null);
        return scopes.readable(actor, false);
    }

    public Set<Long> publiclyVisibleArticles(Set<Long> ids) {
        try {
            return articles.publiclyVisibleIds(ids);
        } catch (DataAccessException e) {
            throw articleUnavailable(e);
        } catch (ContractProblemException e) {
            if (e.getStatus().is5xxServerError()) throw articleUnavailable(e);
            throw e;
        }
    }

    public long authorize(Action action, Long taskId, Long owner) {
        return authorization.authorize(action, taskId, owner);
    }

    public ReadScope reviewableTasks() {
        return scopes.readable(authorization.authorize(Action.REVIEW_READ, null), true);
    }

    public void creationTarget(long actor, cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target target) {
        scopes.creation(actor, target);
    }

    public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor, Audience audience) {
        throw unavailable(Dependency.AUTHORIZATION);
    }

    public List<cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient> resolve(long actor, long taskId, Audience audience) {
        return scopes.resolve(actor, taskId, audience);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private LearningTaskResponsibilities responsibilities;

    public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient recipient(cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {
        return responsibilities.recipient(subject);
    }

    public String own(long actor, cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject subject) {
        return responsibilities.own(actor, subject);
    }

    public String audienceVersion(long actor, long task) {
        return scopes.audienceVersion(actor, task);
    }

    public void finish(long actor, boolean user) {
        scopes.finish(actor, user);
    }

    public void article(long actor, long articleId) {
        try {
            articles.requirePublicReference(articleId);
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException e) {
            if ("/problems/resource-unavailable".equals(e.getType())) throw conflict("resource-unavailable");
            if (e.getStatus().is5xxServerError()) throw articleUnavailable(e);
            throw e;
        }
    }

    private cn.jualn.miniapp.common.exception.ContractProblemException articleUnavailable(RuntimeException cause) {
        var problem = unavailable(Dependency.AUTHORIZATION);
        problem.initCause(cause);
        return problem;
    }

    public void textSafety(long actor, String text) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable(Dependency.CONTENT_SAFETY);
        }
        safety.check(actor, text);
    }

    public void publicationCapabilities(Requirements requirements) {
        if (requirements.maximumFiles() == 0) return;
        if (materials == null) throw unavailable(Dependency.PRIVATE_MEDIA);
        materials.configuredRequirements(requirements.maximumFiles(), requirements.allowedFileKinds().stream().map(Enum::name).toList());
        // Text submission uses the same out-of-transaction trusted safety provider as drafts.
    }

    @Override
    public void preparePublicationCapabilities(Requirements requirements) {
        if (requirements.maximumFiles() == 0) return;
        var current = materials.capabilities();
        if (requirements.maximumFiles() > current.maximumFiles() || !current.availableKinds().containsAll(requirements.allowedFileKinds().stream().map(Enum::name).toList()))
            throw unavailable(Dependency.PRIVATE_MEDIA);
    }

    @Override
    public void requireOtherReviewer(long actor, long submittedBy, String personId) {
        LearningTaskProviders.super.requireOtherReviewer(actor, submittedBy, personId);
        if (personId != null && personId.equals(responsibilities.accountPerson(actor)))
            throw new cn.jualn.miniapp.common.exception.ContractProblemException(org.springframework.http.HttpStatus.FORBIDDEN, "/problems/forbidden", "Self review is not allowed");
    }

    public void bindPrivateFiles(long userId, long assignmentId, long submissionId, List<Long> files, Requirements requirements) {
        if (materials == null) throw unavailable(Dependency.PRIVATE_MEDIA);
        materials.bind(userId, assignmentId, submissionId, files, requirements.allowedFileKinds().stream().map(Enum::name).toList());
    }

    public void accept(List<Notice> notices) {
        try {
            notifications.accept(notices);
        } catch (org.springframework.dao.DataAccessException failure) {
            throw notificationUnavailable(failure);
        }
    }

    public void reconcile(long taskId, LocalDateTime now) {
        try {
            notifications.reconcile(taskId, now);
        } catch (org.springframework.dao.DataAccessException failure) {
            throw notificationUnavailable(failure);
        }
    }

    private cn.jualn.miniapp.common.exception.ContractProblemException notificationUnavailable(RuntimeException failure) {
        var problem = unavailable(Dependency.NOTIFICATION_ACCEPTANCE);
        problem.initCause(failure);
        return problem;
    }
}
