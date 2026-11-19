package cn.jualn.miniapp.module.user.service;

import org.springframework.transaction.annotation.Propagation;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.module.user.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.bo.*;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserAuthRow;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 账号资格、限制及历史角色写入口；不承担资料或微信身份写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountService {
    private final UserProfileMapper userProfileMapper;
    private final RedisService redisService;
    private final cn.jualn.miniapp.module.authorization.service.RbacGovernanceService rootGuard;
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    /** User-owned credential generation. Pre-upgrade credentials without a generation must
     * reauthenticate; otherwise historical failed kickouts could revive after restriction expiry. */
    public long sessionVersion(long userId) {
        Long version = userProfileMapper.selectSessionVersion(userId);
        if (version == null || version < 0) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return version;
    }

    public void requireSessionVersion(long userId, Object claim) {
        long current = sessionVersion(userId);
        if (claim == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        String value = claim.toString();
        if (!Long.toString(current).equals(value)) throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    /** Read the effective restriction without normalizing it inside a caller's write transaction. */
    public boolean isAdminSessionRestricted(long userId) {
        var auth = toUserAuthBO(userProfileMapper.selectUserAuthRowByUserId(userId));
        if (auth == null || (auth.getStatus() != UserStatus.BANNED && auth.getStatus() != UserStatus.MUTED)) {
            return false;
        }
        return auth.getBanExpireAt() == null
                || auth.getBanExpireAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    public static final String SESSION_VERSION = "accountSessionVersion";

    public UserAuthBO getUserAuthInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        // Account eligibility is current database truth, never a cached allow.
        UserAuthBO userAuthBO = toUserAuthBO(userProfileMapper.selectUserAuthRowByUserId(userId));
        if (userAuthBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return normalizeExpiredRestriction(userAuthBO);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assertLoginAllowed(Long userId) {
        if (getUserAuthInfo(userId).getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ResultCode.USER_BANNED);
        }
    }

    public void assertCommunityContentAllowed(long id) { requireCommunity(getUserAuthInfo(id)); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockCommunityWriter(long id) {
        var row=userProfileMapper.lockContentAccount(id);if(row==null)throw new BusinessException(ResultCode.UNAUTHORIZED);
        var auth=toUserAuthBO(row);
        if(auth.getBanExpireAt()!=null && !auth.getBanExpireAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))) auth.setStatus(UserStatus.NORMAL);
        requireCommunity(auth);
    }
    private void requireCommunity(UserAuthBO auth) {
        if(auth.getStatus()==UserStatus.MUTED || auth.getStatus()==UserStatus.BANNED)
            throw cn.jualn.miniapp.common.exception.ContractProblemException.accountContentRestricted(auth.getStatus().name());
    }

    public void assertContentCreationAllowed(Long userId) {
        UserStatus status = getUserAuthInfo(userId).getStatus();
        if (status == UserStatus.BANNED) {
            throw new BusinessException(ResultCode.USER_BANNED);
        }
        if (status == UserStatus.MUTED) {
            throw new BusinessException(ResultCode.USER_MUTED);
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void muteUser(AdminUserRestrictionBO command) {
        authorizeAccountChange(command);
        changeRestriction(command, UserStatus.MUTED, Set.of(UserStatus.NORMAL));
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "muteAdminAccount", "USER", String.valueOf(command.getTargetUserId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void banUser(AdminUserRestrictionBO command) {
        authorizeAccountChange(command);
        changeRestriction(command, UserStatus.BANNED, Set.of(UserStatus.NORMAL, UserStatus.MUTED));
        if (userProfileMapper.invalidateSessions(command.getTargetUserId()) != 1)
            throw new BusinessException(ResultCode.DATA_CONFLICT);
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "banAdminAccount", "USER", String.valueOf(command.getTargetUserId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        afterCommit(() -> {
            try {
                StpUtil.kickout(command.getTargetUserId());
            } catch (RuntimeException e) {
                log.error("[UserAccountService.banUser][会话下线失败] targetUserId={}", command.getTargetUserId(), e);
            }
        });
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void restoreUser(AdminUserRestrictionBO command) {
        authorizeAccountChange(command);
        validateRestrictionCommand(command);
        UserProfile target = requireManageableUser(command);
        managementAudit.confirmed(command.getOperatorId(), "restoreAdminAccount", "USER", target.getId().toString());
        UserStatus currentStatus = getUserAuthInfo(target.getId()).getStatus();
        if (currentStatus == UserStatus.NORMAL) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户当前没有生效中的限制");
        }

        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getStatus, UserStatus.NORMAL.getCode())
                        .set(UserProfile::getBanReason, null)
                        .set(UserProfile::getBanExpireAt, null)
                        .eq(UserProfile::getId, target.getId())
                        .in(UserProfile::getStatus,
                                UserStatus.MUTED.getCode(), UserStatus.BANNED.getCode())
                        .isNull(UserProfile::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户状态已变化，请刷新后重试");
        }
        evictUserProfileCache(target.getId());
        managementAudit.operation(java.util.UUID.randomUUID().toString(), command.getOperatorId(), "restoreAdminAccount", "USER", String.valueOf(command.getTargetUserId()), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("[UserAccountService.restoreUser][完成] operatorId={}, targetUserId={}",
                command.getOperatorId(), target.getId());
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changeUserRole(AdminUserRoleChangeBO command) {
        validateRoleChangeCommand(command);
        // Retain the historical adapter, but no source may write the single-role authority.
        throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict(
                "authorization-source-migration-required", "Use RBAC assignment governance; legacy role writes are frozen");
    }

    private void changeRestriction(
            AdminUserRestrictionBO command,
            UserStatus targetStatus,
            Set<UserStatus> allowedCurrentStatuses) {
        validateRestrictionCommand(command);
        UserProfile target = requireManageableUser(command);
        managementAudit.confirmed(command.getOperatorId(), targetStatus == UserStatus.MUTED ? "muteAdminAccount" : "banAdminAccount", "USER", target.getId().toString());
        UserStatus currentStatus = getUserAuthInfo(target.getId()).getStatus();
        if (!allowedCurrentStatuses.contains(currentStatus)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户当前状态不允许此操作");
        }

        LocalDateTime expireAt = command.getDurationDays() == null
                ? null
                : LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(command.getDurationDays());
        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getStatus, targetStatus.getCode())
                        .set(UserProfile::getBanReason, command.getReason())
                        .set(UserProfile::getBanExpireAt, expireAt)
                        .eq(UserProfile::getId, target.getId())
                        .eq(UserProfile::getStatus, currentStatus.getCode())
                        .isNull(UserProfile::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户状态已变化，请刷新后重试");
        }
        evictUserProfileCache(target.getId());
        log.info("[UserAccountService.changeRestriction][完成] operatorId={}, targetUserId={}, status={}, expireAt={}",
                command.getOperatorId(), target.getId(), targetStatus, expireAt);
    }

    private void validateRestrictionCommand(AdminUserRestrictionBO command) {
        if (command == null || command.getOperatorId() == null || command.getTargetUserId() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "处置参数不能为空");
        }
        if (!StringUtils.hasText(command.getReason()) || command.getReason().length() > 255) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "处置原因不能为空且不能超过255字");
        }
        if (command.getDurationDays() != null && command.getDurationDays() <= 0) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "限制天数必须大于0");
        }
    }

    private void authorizeAccountChange(AdminUserRestrictionBO command) {
        validateRestrictionCommand(command);
        authorization.requirePlatform(command.getOperatorId(), "user:manage", "USER", "USER_ACCOUNT_GOVERN", "user:read", "user:manage");
        rootGuard.protectAccountChange(command.getOperatorId(), command.getTargetUserId());
    }

    private void validateRoleChangeCommand(AdminUserRoleChangeBO command) {
        if (command == null
                || command.getOperatorId() == null
                || command.getTargetUserId() == null
                || command.getTargetRole() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "角色调整参数不能为空");
        }
        if (!StringUtils.hasText(command.getReason()) || command.getReason().length() > 255) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "角色调整原因不能为空且不能超过255字");
        }
    }

    private UserProfile requireManageableUser(AdminUserRestrictionBO command) {
        if (Objects.equals(command.getOperatorId(), command.getTargetUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能处置当前管理员账号");
        }
        UserProfile target = userProfileMapper.selectById(command.getTargetUserId());
        if (target == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return target;
    }

    private UserAuthBO normalizeExpiredRestriction(UserAuthBO auth) {
        LocalDateTime expireAt = auth.getBanExpireAt();
        if (auth.getStatus() == UserStatus.NORMAL
                || expireAt == null
                || expireAt.isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))) {
            return auth;
        }

        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getStatus, UserStatus.NORMAL.getCode())
                        .set(UserProfile::getBanReason, null)
                        .set(UserProfile::getBanExpireAt, null)
                        .eq(UserProfile::getId, auth.getId())
                        .eq(UserProfile::getStatus, auth.getStatus().getCode())
                        .le(UserProfile::getBanExpireAt, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                        .isNull(UserProfile::getDeletedAt));
        evictUserProfileCache(auth.getId());
        if (updated > 0) {
            return UserAuthBO.builder()
                    .id(auth.getId())
                    .role(auth.getRole())
                    .status(UserStatus.NORMAL)
                    .lastLoginAt(auth.getLastLoginAt())
                    .build();
        }

        UserAuthBO latest = toUserAuthBO(userProfileMapper.selectUserAuthRowByUserId(auth.getId()));
        if (latest == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return latest;
    }

    private UserAuthBO toUserAuthBO(UserAuthRow row) {
        if (row == null) {
            return null;
        }
        if (row.getStatus() == null || Arrays.stream(UserStatus.values())
                .noneMatch(status -> status.getCode() == row.getStatus())) {
            throw new BusinessException(ResultCode.INFRASTRUCTURE_ERROR, "当前账号状态无法确认");
        }
        return UserAuthBO.builder()
                .id(row.getId())
                .role(UserRole.fromCode(row.getRole()))
                .status(UserStatus.fromCode(row.getStatus()))
                .banReason(row.getBanReason())
                .banExpireAt(row.getBanExpireAt())
                .lastLoginAt(row.getLastLoginAt())
                .build();
    }

    private void evictUserProfileCache(Long userId) {
        redisService.delete(RedisKeyConstant.userAuthProfile(userId));
        redisService.delete(RedisKeyConstant.userPublicProfile(userId));
        redisService.delete(RedisKeyConstant.userSimpleProfile(userId));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
