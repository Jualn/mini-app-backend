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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.LocalDateTime;
import java.util.*;
import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.*;
import cn.jualn.miniapp.common.exception.ContractProblemException;

@Service
@RequiredArgsConstructor
public class ActivityRegistrationServiceImpl implements ActivityRegistrationService {
    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final ActivityFormAvailability availability;
    private final ActivityParticipationPolicy participationPolicy;
    private final cn.jualn.miniapp.module.timeline.service.TimelineService timelineService;

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
                exists(a.getId()), availability.isEnabled(), state(a, LocalDateTime.now()));
    }

    private String state(Activity a, LocalDateTime now) {
        if (a.getDeletedAt() != null) return "UNAVAILABLE";
        return participationPolicy.evaluate(a.getPublishStatus(), a.getLifecycleStatus(), a.getRegistrationMode(),
                timelineService.listTimelinesByTarget(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY, a.getId()),
                null, count(a.getId()), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO submit(Long activityId, JsonNode data) {
        Long userId = user();
        availability.requireEnabled();
        Activity a = lock(activityId);
        ActivityRegistration existing = mine(activityId, userId);
        JsonNode schema = parse(a.getFormSchema());
        JsonNode normalized = normalizedAnswers(schema, data);
        // A retry of an accepted request returns its receipt even after the window closes.
        if (existing != null && existing.getStatus() == 1) {
            require(normalizedAnswers(schema, parse(existing.getFormData())).equals(normalized), "已提交，请先取消后重新填写");
            return detail(existing, schema);
        }
        require(existing == null || existing.getStatus() == 2, "报名已作废，不能重新提交");
        require("OPEN".equals(state(a, LocalDateTime.now())), "当前不在可提交报名的时间或状态");
        require(Integer.valueOf(1).equals(a.getParticipantMode()), "平台表单仅支持个人");
        require(a.getCapacity() == null || count(activityId) < a.getCapacity(), "平台收表名额已满");
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new ActivityRegistration();
            existing.setActivityId(activityId); existing.setUserId(userId); existing.setFormData(normalized.toString());
            existing.setStatus(1); existing.setFormVersion(a.getFormVersion()); existing.setSubmittedAt(now); existing.setCreatedAt(now); existing.setUpdatedAt(now);
            require(registrationMapper.insert(existing) == 1, "报名提交失败");
            existing = mine(activityId, userId);
        } else {
            require(registrationMapper.restore(existing.getId(), normalized.toString(), now) == 1, "报名状态已变化");
            existing = mine(activityId, userId);
        }
        return detail(existing, schema);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationWriteBO submitContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion) {
        Long userId = user();
        availability.requireEnabled();
        Activity activity = lock(activityId);
        registrationConflict(activity.getFormVersion() != null && activity.getFormVersion().equals(formVersion),
                "registration-form-changed", "表单版本已变化");
        JsonNode schema = parse(activity.getFormSchema());
        JsonNode normalized = normalizedAnswers(schema, answerObject(answers));
        ActivityRegistration existing = mine(activityId, userId);
        if (existing != null && existing.getStatus() == 1) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                    "registration-already-exists", "An active registration already exists");
        }
        registrationConflict(existing == null || existing.getStatus() == 2,
                "registration-state-conflict", "报名状态不允许恢复");
        if (existing != null) requireVersion(expectedVersion, existing);
        assertSubmissionOpen(activity);
        LocalDateTime now = LocalDateTime.now();
        boolean created = existing == null;
        if (created) {
            existing = new ActivityRegistration();
            existing.setActivityId(activityId); existing.setUserId(userId); existing.setFormData(normalized.toString());
            existing.setFormVersion(activity.getFormVersion()); existing.setStatus(1); existing.setSubmittedAt(now);
            existing.setCreatedAt(now); existing.setUpdatedAt(now);
            require(registrationMapper.insert(existing) == 1, "报名提交失败");
        } else {
            if (registrationMapper.restoreVersioned(existing.getId(), version(existing), normalized.toString(),
                    activity.getFormVersion(), now) != 1) throw ContractProblemException.preconditionFailed();
        }
        return new ActivityRegistrationWriteBO(detail(mine(activityId, userId), schema, activity), created);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO replaceMineContract(Long activityId, String formVersion, JsonNode answers, RegistrationVersionBO expectedVersion) {
        Long userId = user(); availability.requireEnabled(); Activity activity = lock(activityId);
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
        registrationConflict("OPEN".equals(state(activity, LocalDateTime.now())),
                "registration-not-open", "当前不在可修改报名的时间或状态");
        JsonNode normalized = normalizedAnswers(schema, answerObject(answers));
        LocalDateTime now = LocalDateTime.now();
        if (registrationMapper.replaceVersioned(existing.getId(), version(existing), normalized.toString(), now) != 1)
            throw ContractProblemException.preconditionFailed();
        return detail(mine(activityId, userId), schema, activity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityRegistrationBO cancelMineContract(Long activityId, RegistrationVersionBO expectedVersion) {
        Long userId = user(); Activity activity = lock(activityId); ActivityRegistration existing = mine(activityId, userId);
        if (existing == null) throw new BusinessException(ResultCode.NOT_FOUND, "报名记录不存在");
        requireVersion(expectedVersion, existing);
        if (existing.getStatus() == 2) return detail(existing, parse(activity.getFormSchema()), activity);
        registrationConflict(existing.getStatus() == 1,
                "registration-state-conflict", "报名状态不可取消");
        if (registrationMapper.cancelVersioned(existing.getId(), version(existing), LocalDateTime.now()) != 1)
            throw ContractProblemException.preconditionFailed();
        return detail(mine(activityId, userId), parse(activity.getFormSchema()), activity);
    }

    private void assertSubmissionOpen(Activity activity) {
        registrationConflict("OPEN".equals(state(activity, LocalDateTime.now())),
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
        lock(activityId);
        ActivityRegistration row = mine(activityId, userId);
        require(row != null, "尚未提交报名");
        if (row.getStatus() == 2) return;
        require(row.getStatus() == 1, "已作废的报名不能取消");
        require(registrationMapper.cancel(row.getId(), LocalDateTime.now()) == 1, "报名状态已变化");
    }

    @Override
    public ActivityRegistrationPageBO pageAdmin(Long activityId, Integer status, String cursor, int pageSize) {
        admin(false); filter(status);
        require(pageSize >= 1 && pageSize <= 100, "分页大小须为1至100");
        require(activityMapper.selectRegistrationActivity(activityId) != null, "活动不存在");
        String sort = "registrations-" + activityId + "-" + status;
        List<ActivityRegistrationBO> rows = registrationMapper.page(activityId, status, AdminIdCursorCodec.decode(cursor, sort), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        return new ActivityRegistrationPageBO(rows, more, more ? AdminIdCursorCodec.encode(sort, rows.get(rows.size() - 1).getId()) : null);
    }

    @Override
    public ActivityRegistrationContractPageBO pageAdminContract(Long activityId, Integer status, int page, int pageSize) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_READ); filterContract(status);
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
    @Transactional(rollbackFor = Exception.class)
    public void invalidate(Long activityId, Long registrationId, String reason) {
        admin(true);
        require(reason != null && !reason.isBlank() && reason.trim().length() <= 255, "作废原因须为1至255字");
        lock(activityId);
        ActivityRegistration row = record(activityId, registrationId);
        if (row.getStatus() == 3) return;
        require(registrationMapper.invalidate(row.getId(), AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), reason.trim(), LocalDateTime.now()) == 1, "报名状态已变化");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public byte[] exportXlsx(Long activityId, Integer status) {
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
    @Transactional(rollbackFor = Exception.class)
    public byte[] exportCsv(Long activityId, Integer status) {
        AdminStpUtil.STP_LOGIC.checkPermission(AdminPermissionPolicy.ACTIVITY_REGISTRATION_EXPORT);
        filterContract(status); Activity activity = lock(activityId);
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
            require(Objects.equals(parse(oldSchema), parse(newSchema)), "已有报名记录，整份表单定义已冻结");
            require(Objects.equals(oldMode, newMode) && Integer.valueOf(1).equals(participantMode), "已有报名记录，不能改变报名方式或个人参与形式");
        }
        require(limit == null || count(id) <= limit, "限额不能低于当前有效提交数");
    }

    private Activity lock(Long id) {
        Activity a = activityMapper.lockRegistrationActivity(id);
        require(a != null, "活动不存在");
        return a;
    }
    private boolean exists(Long id) { return registrationMapper.exists(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id)); }
    private long count(Long id) { return registrationMapper.selectCount(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getStatus, 1)); }

    @Override
    public java.util.Map<Long, Long> countSubmittedByActivityIds(java.util.Collection<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return java.util.Map.of();
        return registrationMapper.countSubmittedByActivityIds(activityIds).stream().collect(java.util.stream.Collectors.toMap(
                cn.jualn.miniapp.module.activity.bo.ActivityRegistrationCountBO::getActivityId,
                cn.jualn.miniapp.module.activity.bo.ActivityRegistrationCountBO::getSubmittedCount));
    }
    private ActivityRegistration mine(Long id, Long userId) { return registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getUserId, userId)); }
    private ActivityRegistration record(Long id, Long registrationId) {
        ActivityRegistration row = registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getId, registrationId).eq(ActivityRegistration::getActivityId, id));
        require(row != null, "报名记录不存在"); return row;
    }
    private ActivityRegistrationBO detail(ActivityRegistration r, JsonNode schema) {
        return detail(r, schema, null);
    }
    private ActivityRegistrationBO detail(ActivityRegistration r, JsonNode schema, Activity activity) {
        ActivityRegistrationBO b = new ActivityRegistrationBO();
        b.setId(r.getId()); b.setActivityId(r.getActivityId()); b.setUserId(r.getUserId()); b.setStatus(r.getStatus());
        b.setContractVersion(r.getContractVersion()); b.setFormVersion(r.getFormVersion()); b.setUpdatedAt(r.getUpdatedAt());
        b.setSubmittedAt(r.getSubmittedAt()); b.setCancelledAt(r.getCancelledAt()); b.setInvalidatedAt(r.getInvalidatedAt());
        b.setInvalidatedBy(r.getInvalidatedBy()); b.setInvalidReason(r.getInvalidReason()); b.setFormData(parse(r.getFormData()));
        List<ActivityRegistrationBO.Answer> answers = new ArrayList<>();
        for (JsonNode f : schema.get("fields")) {
            String key = cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.keyOf(f);
            answers.add(new ActivityRegistrationBO.Answer(key, f.get("label").asText(),
                    cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.typeOf(f), display(f, b.getFormData().get(key))));
        }
        b.setCanCancel(Integer.valueOf(1).equals(r.getStatus()));
        b.setCanModify(Integer.valueOf(1).equals(r.getStatus()) && schema.path("allowModification").asBoolean(true)
                && activity != null && "OPEN".equals(state(activity, LocalDateTime.now())));
        b.setAnswers(answers); return b;
    }
    private static boolean platform(Integer mode) { return Integer.valueOf(2).equals(mode) || Integer.valueOf(4).equals(mode); }
    private static Long user() { Long id = UserContext.getUserId(); if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED); return id; }
    private static void admin(boolean edit) { AdminStpUtil.STP_LOGIC.checkPermission(edit ? AdminPermissionPolicy.ACTIVITY_EDIT : AdminPermissionPolicy.ACTIVITY_READ); }
    private static void filter(Integer status) { require(status == null || Set.of(1, 2, 3).contains(status), "报名状态不合法"); }
    private static void filterContract(Integer status) { require(status == null || Set.of(1, 2).contains(status), "报名状态不合法"); }
}
