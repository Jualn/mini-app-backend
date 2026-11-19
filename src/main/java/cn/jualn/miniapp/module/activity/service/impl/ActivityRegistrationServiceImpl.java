package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.mapper.ActivityRegistrationMapper;
import cn.jualn.miniapp.module.activity.service.*;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.*;

import cn.jualn.miniapp.common.exception.ContractProblemException;

@Service
@RequiredArgsConstructor
public class ActivityRegistrationServiceImpl implements ActivityRegistrationService {
    private final CurrentAuthorization authorization;
    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final ActivityParticipationPolicy participationPolicy;
    private final TimelineService timelineService;
    private final ActivityPublicHistory publicHistory;
    private final ActivityReminderCatchUp reminderCatchUp;

    @Override
    public ActivityFormBO getForm(Long activityId) {
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null && a.getDeletedAt() == null
                        && Integer.valueOf(1).equals(a.getPublishStatus()),
                "活动不可访问");
        return form(a);
    }

    @Override
    public ActivityFormBO getAdminForm(Long activityId) {
        admin(false);
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null, "活动不存在");
        return form(a);
    }

    private ActivityFormBO form(Activity a) {
        return new ActivityFormBO(a.getId(), a.getFormVersion(), parse(a.getFormSchema()), a.getCapacity(), count(a.getId()),
                exists(a.getId()), true, state(a, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    private String state(Activity a, LocalDateTime now) {
        if (a.getDeletedAt() != null) return "UNAVAILABLE";
        return participationPolicy.evaluate(a.getPublishStatus(), a.getLifecycleStatus(), a.getRegistrationMode(),
                timelineService.listTimelinesByTarget(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY, a.getId()),
                null, count(a.getId()), now, Boolean.TRUE.equals(a.getAllowRegistrationAfterStart()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO submit(Long activityId, JsonNode data) {
        Long userId = user();
        Activity a = lock(activityId);
        ActivityRegistration existing = mine(activityId, userId);
        assertNotInvalidated(existing);
        JsonNode schema = parse(a.getFormSchema());
        JsonNode normalized = normalizedAnswers(schema, data);
        // A retry of an accepted request returns its receipt even after the window closes.
        if (existing != null && existing.getStatus() == 1) {
            require(normalizedAnswers(schema, parse(existing.getFormData())).equals(normalized), "已提交，请先取消后重新填写");
            return detail(existing, schema);
        }
        require(existing == null || (existing.getStatus() == 2 || existing.getStatus() == 3), "报名已作废，不能重新提交");
        require("OPEN".equals(state(a, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))), "当前不在可提交报名的时间或状态");
        require(Integer.valueOf(1).equals(a.getParticipantMode()), "平台表单仅支持个人");
        require(a.getCapacity() == null || count(activityId) < a.getCapacity(), "平台收表名额已满");
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (existing == null) {
            existing = new ActivityRegistration();
            existing.setActivityId(activityId);
            existing.setUserId(userId);
            existing.setFormData(normalized.toString());
            existing.setStatus(1);
            existing.setFormVersion(a.getFormVersion());
            existing.setSubmittedAt(now);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            require(registrationMapper.insert(existing) == 1, "报名提交失败");
            existing = mine(activityId, userId);
        } else {
            require(registrationMapper.restore(existing.getId(), normalized.toString(), now) == 1, "报名状态已变化");
            existing = mine(activityId, userId);
        }
        reminderCatchUp.joined(a, userId, true);
        return detail(existing, schema);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationWriteBO submitContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion) {
        Long userId = user();
        Activity activity = lock(activityId);
        registrationConflict(activity.getFormVersion() != null && activity.getFormVersion().equals(formVersion),
                "registration-form-changed", "表单版本已变化");
        ActivityRegistration existing = mine(activityId, userId);
        assertNotInvalidated(existing);
        JsonNode schema = parse(activity.getFormSchema());
        JsonNode normalized = normalizedAnswers(schema, answerObject(answers));
        if (existing != null && existing.getStatus() == 1) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "registration-already-exists", "An active registration already exists");
        }
        registrationConflict(existing == null || (existing.getStatus() == 2 || existing.getStatus() == 3),
                "registration-state-conflict", "报名状态不允许恢复");
        if (existing != null) requireVersion(expectedVersion, existing);
        assertSubmissionOpen(activity);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        boolean created = existing == null;
        if (created) {
            existing = new ActivityRegistration();
            existing.setActivityId(activityId);
            existing.setUserId(userId);
            existing.setFormData(normalized.toString());
            existing.setFormVersion(activity.getFormVersion());
            existing.setStatus(1);
            existing.setSubmittedAt(now);
            existing.setCreatedAt(now);
            existing.setUpdatedAt(now);
            require(registrationMapper.insert(existing) == 1, "报名提交失败");
        } else {
            if (registrationMapper.restoreVersioned(existing.getId(), version(existing), normalized.toString(),
                    activity.getFormVersion(), now) != 1) throw ContractProblemException.preconditionFailed();
        }
        reminderCatchUp.joined(activity, userId, true);
        return new ActivityRegistrationWriteBO(detail(mine(activityId, userId), schema, activity), created);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO replaceMineContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion) {
        Long userId = user();
        Activity activity = lock(activityId);
        registrationConflict(activity.getFormVersion() != null && activity.getFormVersion().equals(formVersion),
                "registration-form-changed", "表单版本已变化");
        ActivityRegistration existing = mine(activityId, userId);
        if (existing == null) throw new BusinessException(ResultCode.NOT_FOUND, "报名记录不存在");
        registrationConflict(existing.getStatus() == 1,
                "registration-state-conflict", "已取消报名不能通过修改接口恢复");
        requireVersion(expectedVersion, existing);
        JsonNode schema = parse(activity.getFormSchema());
        registrationConflict(schema.path("allowModification").asBoolean(true),
                "registration-modification-disabled", "当前表单不允许修改");
        registrationConflict("OPEN".equals(state(activity, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))),
                "registration-not-open", "当前不在可修改报名的时间或状态");
        JsonNode normalized = normalizedAnswers(schema, answerObject(answers));
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (registrationMapper.replaceVersioned(existing.getId(), version(existing), normalized.toString(), now) != 1)
            throw ContractProblemException.preconditionFailed();
        return detail(mine(activityId, userId), schema, activity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO cancelMineContract(Long activityId, RegistrationVersionBO expectedVersion) {
        Long userId = user();
        Activity activity = lock(activityId);
        ActivityRegistration existing = mine(activityId, userId);
        if (existing == null) throw new BusinessException(ResultCode.NOT_FOUND, "报名记录不存在");
        requireVersion(expectedVersion, existing);
        if ((existing.getStatus() == 2 || existing.getStatus() == 3)) return detail(existing, parse(activity.getFormSchema()), activity);
        registrationConflict(existing.getStatus() == 1,
                "registration-state-conflict", "报名状态不可取消");
        registrationConflict(canCancel(activity), "registration-cancellation-closed", "当前活动已关闭取消报名");
        if (registrationMapper.cancelVersioned(existing.getId(), version(existing), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) != 1)
            throw ContractProblemException.preconditionFailed();
        return detail(mine(activityId, userId), parse(activity.getFormSchema()), activity);
    }

    private void assertSubmissionOpen(Activity activity) {
        registrationConflict("OPEN".equals(state(activity, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))),
                "registration-not-open", "当前不在可提交报名的时间或状态");
        registrationConflict(Integer.valueOf(1).equals(activity.getParticipantMode()),
                "registration-not-open", "平台表单仅支持个人及明确截止时刻");
        long submitted = count(activity.getId());
        Integer contractCapacity = Integer.valueOf(1).equals(activity.getCapacityUnit()) ? activity.getCapacity() : null;
        registrationConflict((activity.getCapacity() == null || submitted < activity.getCapacity())
                        && (contractCapacity == null || submitted < contractCapacity),
                "activity-full", "平台收表名额已满");
    }

    private JsonNode answerObject(JsonNode answers) {
        require(answers != null && answers.isArray() && answers.size() <= 30, "answers必须为数组");
        var object = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (JsonNode answer : answers) {
            require(answer.isObject() && answer.size() == 2 && answer.has("fieldKey") && answer.has("value"), "答案结构不合法");
            String key = answer.path("fieldKey").asText();
            require(!key.isBlank() && !object.has(key) && !answer.get("value").isNull(), "答案字段重复、为空或缺失");
            object.set(key, answer.get("value"));
        }
        return object;
    }

    private long version(ActivityRegistration registration) {
        return Objects.requireNonNull(registration.getContractVersion(), "registration version");
    }

    private void requireVersion(RegistrationVersionBO expectedVersion, ActivityRegistration registration) {
        if (expectedVersion == null) {
            throw ContractProblemException.preconditionRequired();
        }
        if (expectedVersion.registrationId() != registration.getId()
                || expectedVersion.version() != version(registration)) {
            throw ContractProblemException.preconditionFailed();
        }
    }

    private static void registrationConflict(boolean valid, String type, String detail) {
        if (!valid) throw ContractProblemException.conflict(type, detail);
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MyActivityRegistrationPageBO pageMine(String cursor, Integer status, int pageSize) {
        long userId = user();
        filterContract(status);
        require(pageSize >= 1 && pageSize <= 100, "分页大小须为1至100");
        var seek = ActivityRegistrationCursor.decode(cursor, userId, status);
        var rows = registrationMapper.pageMine(userId, status, seek == null ? null : seek.submittedAt(),
                seek == null ? null : seek.id(), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        var items = rows.stream().map(row -> {
            Activity activity = activityMapper.selectRegistrationActivity(row.getActivityId());
            if (activity == null) throw new IllegalStateException("Historical registration has no activity");
            return new MyActivityRegistrationPageBO.Item(publicHistory.read(activity, now),
                    detail(row, parse(activity.getFormSchema()), activity));
        }).toList();
        String next = more ? ActivityRegistrationCursor.encode(userId, status, rows.get(rows.size() - 1)) : null;
        return new MyActivityRegistrationPageBO(items, next);
    }

    @Override
    public ActivityRegistrationBO getMine(Long activityId) {
        ActivityRegistration row = mine(activityId, user());
        if (row == null) return null;
        // Historical answers remain readable after cancellation, take-down and soft deletion.
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null, "活动不存在");
        return detail(row, parse(a.getFormSchema()), a);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelMine(Long activityId) {
        Long userId = user();
        Activity activity = lock(activityId);
        ActivityRegistration row = mine(activityId, userId);
        require(row != null, "尚未提交报名");
        if (row.getStatus() == 2) return;
        require(row.getStatus() == 1, "已作废的报名不能取消");
        registrationConflict(canCancel(activity), "registration-cancellation-closed", "当前活动已关闭取消报名");
        require(registrationMapper.cancel(row.getId(), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) == 1, "报名状态已变化");
    }

    @Override
    public ActivityRegistrationPageBO pageAdmin(Long activityId, Integer status, String cursor, int pageSize) {
        authorization.requireActivityRegistrationRead(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong());

        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        filter(status);
        require(pageSize >= 1 && pageSize <= 100, "分页大小须为1至100");
        require(activityMapper.selectRegistrationActivity(activityId) != null, "活动不存在");
        String sort = "registrations-" + activityId + "-" + status;
        List<ActivityRegistrationBO> rows = registrationMapper.page(activityId, status, AdminIdCursorCodec.decode(cursor, sort), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        return new ActivityRegistrationPageBO(rows, more, more ? AdminIdCursorCodec.encode(sort, rows.get(rows.size() - 1).getId()) : null);
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ActivityRegistrationCandidatePageBO pageCandidates(long operator, String q, int page, int pageSize) {
        authorization.requireActivityRegistrationRead(operator);
        String keyword = q == null ? null : q.strip();
        if (q != null && (keyword.isEmpty() || q.codePointCount(0, q.length()) > 200)) {
            throw cn.jualn.miniapp.common.web.AdminQuery.invalid("q");
        }
        if (page < 1) throw cn.jualn.miniapp.common.web.AdminQuery.invalid("page");
        if (pageSize < 1 || pageSize > 100) throw cn.jualn.miniapp.common.web.AdminQuery.invalid("pageSize");
        // The current registration permission covers the explicit Activity platform as a whole.
        long total = activityMapper.countRegistrationCandidates(keyword);
        long offset = ((long) page - 1) * pageSize;
        return new ActivityRegistrationCandidatePageBO(offset >= total ? List.of()
                : activityMapper.registrationCandidates(keyword, offset, pageSize), page, pageSize, total);
    }

    @Override
    public ActivityRegistrationContractPageBO pageAdminContract(Long activityId, Integer status, int page, int pageSize) {
        authorization.requireActivityRegistrationRead(AdminStpUtil.STP_LOGIC.getLoginIdAsLong());

        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ);
        filterContract(status);
        require(page >= 1 && pageSize >= 1 && pageSize <= 100, "分页参数不合法");
        Activity activity = activityMapper.selectRegistrationActivity(activityId);
        require(activity != null, "活动不存在");
        if (!platform(activity.getRegistrationMode()) || activity.getFormSchema() == null) {
            return new ActivityRegistrationContractPageBO(List.of(), page, pageSize, 0);
        }
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        List<ActivityRegistrationBO> items = registrationMapper.pageContract(activityId, status, offset, pageSize)
                .stream().map(row -> detail(row, parse(activity.getFormSchema()))).toList();
        return new ActivityRegistrationContractPageBO(items, page, pageSize,
                registrationMapper.countContract(activityId, status));
    }

    @Override
    public ActivityRegistrationBO getAdminRegistration(Long activityId, Long registrationId) {
        admin(false);
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null, "活动不存在");
        return detail(record(activityId, registrationId), parse(a.getFormSchema()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void invalidate(Long activityId, Long registrationId, String reason) {
        // Retained internal callers must also supply a read version; no unconditional writes.
        throw ContractProblemException.preconditionRequired();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ActivityRegistrationBO invalidateContract(Long activityId, Long registrationId, String reason,
                                                      RegistrationVersionBO expected) {
        long actor = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        authorization.requireWork(actor, "ACTIVITY_REGISTRATION_DISPOSE", "ACTIVITY",
                "activity:registration:read", "activity:registration:dispose");
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 500)
            throw ContractProblemException.validation(new ContractProblemException.Violation("body", "/reason", "INVALID", "A reason of 1 to 500 characters is required"));
        Activity activity = activityMapper.lockRegistrationActivity(activityId);
        if (activity == null) throw new BusinessException(ResultCode.NOT_FOUND);
        ActivityRegistration row = registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery()
                .eq(ActivityRegistration::getActivityId, activityId).eq(ActivityRegistration::getId, registrationId));
        if (row == null) throw new BusinessException(ResultCode.NOT_FOUND);
        requireVersion(expected, row);
        if (row.getStatus() == 2 || row.getStatus() == 3) return detail(row, parse(activity.getFormSchema()));
        registrationConflict(row.getStatus() == 1 && invalidationOpen(activity),
                "registration-cancellation-closed", "The registration can no longer be cancelled");
        if (registrationMapper.invalidate(row.getId(), actor, reason.trim(),
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) != 1) throw ContractProblemException.preconditionFailed();
        return detail(record(activityId, registrationId), parse(activity.getFormSchema()));
    }

    private boolean invalidationOpen(Activity activity) {
        var timeline=timelineService.listTimelinesByTarget(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY,activity.getId());
        if (!Boolean.TRUE.equals(activity.getAllowRegistrationAfterStart()) && ActivityStagePolicy.main(timeline)==null) return false;
        return participationPolicy.canCancel(activity.getLifecycleStatus(),activity.getDeletedAt()!=null,
                Boolean.TRUE.equals(activity.getAllowRegistrationAfterStart()),timeline,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    private static void assertNotInvalidated(ActivityRegistration row) {
        if (row != null && (Integer.valueOf(3).equals(row.getStatus()) || row.getInvalidatedAt() != null))
            throw ContractProblemException.conflict("registration-invalidated", "An administratively invalidated registration cannot be restored");
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public byte[] exportXlsx(Long activityId, Integer status) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "activity:registration:export", "ACTIVITY", "activity:registration:export");

        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_EXPORT);
        filterContract(status);
        // Registration writes and form edits take the same activity lock, so schema and rows form one snapshot.
        Activity activity = lock(activityId);
        long rowCount = registrationMapper.countContract(activityId, status);
        if (rowCount > ActivityRegistrationExport.MAX_DATA_ROWS) {
            throw new cn.jualn.miniapp.common.exception.SystemException(
                    "活动报名 XLSX 超过工作表行数上限: activityId=" + activityId + ", rows=" + rowCount);
        }
        List<ActivityRegistration> rows = registrationMapper.exportContract(activityId, status);
        if (rows.size() != rowCount) {
            throw new cn.jualn.miniapp.common.exception.SystemException(
                    "活动报名 XLSX 快照行数不一致: activityId=" + activityId
                            + ", expected=" + rowCount + ", actual=" + rows.size());
        }
        return ActivityRegistrationExport.xlsx(activity.getFormVersion(), parseFrozenSchema(activity), rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public byte[] exportCsv(Long activityId, Integer status) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "activity:registration:export", "ACTIVITY", "activity:registration:export");

        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_EXPORT);
        filterContract(status);
        Activity activity = lock(activityId);
        if (!platform(activity.getRegistrationMode()) || activity.getFormSchema() == null) {
            return ActivityRegistrationExport.csv(null, List.of());
        }
        return ActivityRegistrationExport.csv(parse(activity.getFormSchema()),
                registrationMapper.exportContract(activityId, status));
    }

    private JsonNode parseFrozenSchema(Activity activity) {
        if (activity.getFormVersion() == null) return null;
        if (activity.getFormSchema() == null) {
            throw new cn.jualn.miniapp.common.exception.SystemException(
                    "已冻结报名表单缺少定义: activityId=" + activity.getId()
                            + ", formVersion=" + activity.getFormVersion());
        }
        try {
            JsonNode schema = parse(activity.getFormSchema());
            ActivityFormPolicy.schema(schema);
            return schema;
        } catch (RuntimeException exception) {
            throw new cn.jualn.miniapp.common.exception.SystemException(
                    "无法读取冻结报名表单: activityId=" + activity.getId()
                            + ", formVersion=" + activity.getFormVersion(), exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateFormChange(Long id, String oldSchema, String newSchema, Integer limit,
                                   Integer oldMode, Integer newMode, Integer participantMode) {
        require(limit == null || limit > 0, "平台收表限额必须大于0");
        if (newSchema != null) schema(parse(newSchema));
        if (exists(id)) {
            registrationConflict(platform(oldMode) && platform(newMode) && Integer.valueOf(1).equals(participantMode),
                    "registration-configuration-conflict", "已有报名历史，不能移除平台报名能力或改变个人参与形式");
            registrationConflict(oldSchema != null && newSchema != null
                    && parse(oldSchema).path("fields").equals(parse(newSchema).path("fields")), "form-locked", "已有报名记录，表单字段已冻结");
        }
        registrationConflict(limit == null || count(id) <= limit, "registration-configuration-conflict", "限额不能低于当前有效提交数");
    }

    private Activity lock(Long id) {
        Activity a = activityMapper.lockRegistrationActivity(id);
        require(a != null, "活动不存在");
        return a;
    }

    private boolean exists(Long id) {
        return registrationMapper.exists(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id));
    }

    private long count(Long id) {
        return registrationMapper.selectCount(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getStatus, 1));
    }

    @Override
    public java.util.Map<Long, Long> countSubmittedByActivityIds(Collection<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return Map.of();
        return registrationMapper.countSubmittedByActivityIds(activityIds).stream().collect(Collectors.toMap(
                ActivityRegistrationCountBO::getActivityId,
                ActivityRegistrationCountBO::getSubmittedCount));
    }

    private ActivityRegistration mine(Long id, Long userId) {
        return registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getUserId, userId));
    }

    private ActivityRegistration record(Long id, Long registrationId) {
        ActivityRegistration row = registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getId, registrationId).eq(ActivityRegistration::getActivityId, id));
        require(row != null, "报名记录不存在");
        return row;
    }

    private ActivityRegistrationBO detail(ActivityRegistration r, JsonNode schema) {
        return detail(r, schema, null);
    }

    private ActivityRegistrationBO detail(ActivityRegistration r, JsonNode schema, Activity activity) {
        if (schema == null) schema = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        ActivityRegistrationBO b = new ActivityRegistrationBO();
        b.setId(r.getId());
        b.setActivityId(r.getActivityId());
        b.setUserId(r.getUserId());
        b.setStatus(r.getStatus() == 3 ? 2 : r.getStatus());
        b.setContractVersion(r.getContractVersion());
        b.setFormVersion(r.getFormVersion());
        b.setUpdatedAt(r.getUpdatedAt());
        b.setSubmittedAt(r.getSubmittedAt());
        b.setCancelledAt(r.getStatus() == 1 ? null : (r.getCancelledAt() != null ? r.getCancelledAt() : r.getInvalidatedAt()));
        if (b.getStatus() == 2 && b.getCancelledAt() == null)
            throw new cn.jualn.miniapp.common.exception.SystemException("Historical cancelled registration has no trustworthy cancellation time: " + r.getId());
        b.setInvalidatedAt(r.getInvalidatedAt());
        b.setInvalidatedBy(r.getInvalidatedBy());
        b.setInvalidReason(r.getInvalidReason());
        b.setFormData(parse(r.getFormData()));
        List<ActivityRegistrationBO.Answer> answers = new ArrayList<>();
        for (JsonNode f : schema.path("fields")) {
            String key = cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.keyOf(f);
            answers.add(new ActivityRegistrationBO.Answer(key, f.get("label").asText(),
                    cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.typeOf(f), display(f, b.getFormData().get(key))));
        }
        b.setCanCancel(Integer.valueOf(1).equals(r.getStatus()) && activity != null && canCancel(activity));
        b.setCanModify(Integer.valueOf(1).equals(r.getStatus()) && schema.path("allowModification").asBoolean(true)
                && activity != null && "OPEN".equals(state(activity, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))));
        b.setAnswers(answers);
        return b;
    }

    private boolean canCancel(Activity activity) {
        return participationPolicy.canCancel(activity.getLifecycleStatus(), activity.getDeletedAt() != null,
                Boolean.TRUE.equals(activity.getAllowRegistrationAfterStart()),
                timelineService.listTimelinesByTarget(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY, activity.getId()),
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    private static boolean platform(Integer mode) {
        return Integer.valueOf(2).equals(mode) || Integer.valueOf(4).equals(mode);
    }

    private static Long user() {
        Long id = UserContext.getUserId();
        if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return id;
    }

    private void admin(boolean edit) {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),
                edit ? AdminPermissionPolicy.ACTIVITY_EDIT : AdminPermissionPolicy.ACTIVITY_READ,
                "ACTIVITY", edit ? "activity:registration:dispose" : "activity:registration:read");
        AdminStpUtil.STP_LOGIC.checkPermission(edit ? AdminPermissionPolicy.ACTIVITY_EDIT : AdminPermissionPolicy.ACTIVITY_READ);
    }

    private static void filter(Integer status) {
        require(status == null || Set.of(1, 2, 3).contains(status), "报名状态不合法");
    }

    private static void filterContract(Integer status) {
        require(status == null || Set.of(1, 2).contains(status), "报名状态不合法");
    }
}
