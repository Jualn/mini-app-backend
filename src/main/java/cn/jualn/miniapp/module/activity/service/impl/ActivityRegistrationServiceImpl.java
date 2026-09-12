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

@Service
@RequiredArgsConstructor
public class ActivityRegistrationServiceImpl implements ActivityRegistrationService {
    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final ActivityFormAvailability availability;

    @Override
    public ActivityFormBO getForm(Long activityId) {
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null && a.getDeletedAt() == null && Integer.valueOf(1).equals(a.getPublishStatus()), "活动不可访问");
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
        return new ActivityFormBO(a.getId(), parse(a.getFormSchema()), a.getRegistrationLimit(), count(a.getId()),
                exists(a.getId()), availability.isEnabled(), state(a, LocalDateTime.now()));
    }

    private String state(Activity a, LocalDateTime now) {
        if (a.getDeletedAt() != null || !platform(a.getRegistrationMode())) return "UNAVAILABLE";
        return availability.registrationStatus(a.getPublishStatus() == null ? 0 : a.getPublishStatus(),
                a.getRegistrationMode(), a.getRegistrationStart(), a.getRegistrationStartPrecision(),
                a.getRegistrationEnd(), a.getRegistrationEndPrecision(), now);
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
        require(Integer.valueOf(1).equals(a.getParticipantMode()) && Integer.valueOf(2).equals(a.getRegistrationEndPrecision()), "平台表单仅支持个人及明确截止时刻");
        require(a.getRegistrationLimit() == null || count(activityId) < a.getRegistrationLimit(), "平台收表名额已满");
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new ActivityRegistration();
            existing.setActivityId(activityId); existing.setUserId(userId); existing.setFormData(normalized.toString());
            existing.setStatus(1); existing.setSubmittedAt(now); existing.setCreatedAt(now); existing.setUpdatedAt(now);
            require(registrationMapper.insert(existing) == 1, "报名提交失败");
        } else {
            require(registrationMapper.restore(existing.getId(), normalized.toString(), now) == 1, "报名状态已变化");
            existing.setStatus(1); existing.setFormData(normalized.toString()); existing.setSubmittedAt(now); existing.setCancelledAt(null);
        }
        return detail(existing, schema);
    }

    @Override
    public ActivityRegistrationBO getMine(Long activityId) {
        ActivityRegistration row = mine(activityId, user());
        if (row == null) return null;
        // Historical answers remain readable after cancellation, take-down and soft deletion.
        Activity a = activityMapper.selectRegistrationActivity(activityId);
        require(a != null, "活动不存在");
        return detail(row, parse(a.getFormSchema()));
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
    public byte[] export(Long activityId, Integer status) {
        admin(false); filter(status);
        // Every registration mutation and form edit takes this lock. The bounded read is a single snapshot.
        Activity a = lock(activityId);
        List<ActivityRegistration> rows = registrationMapper.exportRows(activityId, status, 1001);
        require(rows.size() <= 1000, "导出超过1000条，请按状态缩小范围");
        require(rows.stream().mapToLong(r -> r.getFormData().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum() <= 8 * 1024 * 1024, "导出答案超过8MiB，请缩小范围");
        return ActivityRegistrationExport.xlsx(parse(a.getFormSchema()), rows, LocalDateTime.now());
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
    private ActivityRegistration mine(Long id, Long userId) { return registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getUserId, userId)); }
    private ActivityRegistration record(Long id, Long registrationId) {
        ActivityRegistration row = registrationMapper.selectOne(Wrappers.<ActivityRegistration>lambdaQuery().eq(ActivityRegistration::getId, registrationId).eq(ActivityRegistration::getActivityId, id));
        require(row != null, "报名记录不存在"); return row;
    }
    private ActivityRegistrationBO detail(ActivityRegistration r, JsonNode schema) {
        ActivityRegistrationBO b = new ActivityRegistrationBO();
        b.setId(r.getId()); b.setActivityId(r.getActivityId()); b.setUserId(r.getUserId()); b.setStatus(r.getStatus());
        b.setSubmittedAt(r.getSubmittedAt()); b.setCancelledAt(r.getCancelledAt()); b.setInvalidatedAt(r.getInvalidatedAt());
        b.setInvalidatedBy(r.getInvalidatedBy()); b.setInvalidReason(r.getInvalidReason()); b.setFormData(parse(r.getFormData()));
        List<ActivityRegistrationBO.Answer> answers = new ArrayList<>();
        for (JsonNode f : schema.get("fields")) answers.add(new ActivityRegistrationBO.Answer(f.get("key").asText(), f.get("label").asText(), f.get("type").asText(), display(f, b.getFormData().get(f.get("key").asText()))));
        b.setAnswers(answers); return b;
    }
    private static boolean platform(Integer mode) { return Integer.valueOf(2).equals(mode) || Integer.valueOf(4).equals(mode); }
    private static Long user() { Long id = UserContext.getUserId(); if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED); return id; }
    private static void admin(boolean edit) { AdminStpUtil.STP_LOGIC.checkPermission(edit ? AdminPermissionPolicy.ACTIVITY_EDIT : AdminPermissionPolicy.ACTIVITY_READ); }
    private static void filter(Integer status) { require(status == null || Set.of(1, 2, 3).contains(status), "报名状态不合法"); }
}
