package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ContractProblemException.Violation;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService;
import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.*;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.event.UserCreatedEvent;
import cn.jualn.miniapp.module.user.mapper.UserAgreementMapper;
import cn.jualn.miniapp.module.user.mapper.AdminUserDetailRow;
import cn.jualn.miniapp.module.user.mapper.AdminUserListRow;
import cn.jualn.miniapp.module.user.mapper.AdminUserSummaryRow;
import cn.jualn.miniapp.module.user.mapper.UserAuthRow;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserProfileMapper userProfileMapper;
    private final UserAgreementMapper userAgreementMapper;
    private final RedisService redisService;
    private final UserConverter userConverter;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaService mediaService;
    private final AdminOperationLogService adminOperationLogService;

    private final ProfileSafetyCheckService profileSafety;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.user-profile.writes-enabled:false}")
    private boolean profileWritesEnabled;

    @Override
    public EffectiveProfileBO getEffectiveProfile(Long userId) {
        Long actorId = requireUserId();
        UserProfile actor = requireProfile(actorId);
        assertProfileAllowed(actor);
        return effectiveProfile(userId == null || actorId.equals(userId) ? actor : requireProfile(userId));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public EffectiveProfileBO updateEffectiveProfile(UserProfileUpdateBO command) {
        if (command == null || command.getAvatarUrl() != null || command.getBackgroundUrl() != null
                || command.getBackgroundObjectKey() != null || command.getGender() != null) {
            throw invalidProfile("/", "INVALID", "仅允许修改昵称、头像引用和简介");
        }
        UserProfileBO saved = updateCurrentProfile(command);
        return EffectiveProfileBO.builder().userId(saved.getId()).nickname(saved.getNickname())
                .avatarUrl(StringUtils.hasText(saved.getAvatarUrl()) ? saved.getAvatarUrl() : null)
                .bio(Objects.requireNonNullElse(saved.getBio(), ""))
                .platformOperator(saved.getRole() == UserRole.OPR || saved.getRole() == UserRole.ADMIN).build();
    }

    private UserProfile requireProfile(Long userId) {
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);
        return profile;
    }

    private EffectiveProfileBO effectiveProfile(UserProfile profile) {
        if (profile.getNickname() == null) {
            throw new IllegalStateException("Profile nickname requires historical-data remediation");
        }
        return EffectiveProfileBO.builder().userId(profile.getId()).nickname(profile.getNickname())
                .avatarUrl(StringUtils.hasText(profile.getAvatarUrl()) ? profile.getAvatarUrl() : null)
                .bio(Objects.requireNonNullElse(profile.getBio(), ""))
                .platformOperator(Objects.equals(profile.getRole(), UserRole.OPR.getCode())
                        || Objects.equals(profile.getRole(), UserRole.ADMIN.getCode())).build();
    }

    private void assertProfileAllowed(UserProfile profile) {
        if (Objects.equals(profile.getStatus(), UserStatus.BANNED.getCode())
                && (profile.getBanExpireAt() == null || profile.getBanExpireAt().isAfter(LocalDateTime.now()))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 根据微信小程序 openid 获取用户基本信息。
     *
     * @param openid 微信小程序openid
     * @return 用户基本信息，用于拼接给登录返回
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoDTO getUserInfo(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "openid 不能为空");
        }

        UserProfile userProfile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getId, UserProfile::getNickname,
                                UserProfile::getAvatarUrl, UserProfile::getRole)
                        .eq(UserProfile::getOpenid, openid)
        );

        if (userProfile == null) {
            // 首次登录自动注册。
            String defaultUserName = "用户" + UUID.randomUUID().toString().substring(0, 6);
            userProfile = UserProfile.builder()
                    .openid(openid)
                    .nickname(defaultUserName)
                    .role(UserRole.USER.getCode())  // 只会回填 id，这里设置避免下方转化拿到null
                    .build();
            userProfileMapper.insert(userProfile);
            eventPublisher.publishEvent(new UserCreatedEvent(userProfile.getId()));
        }

        return userConverter.toUserInfoDTO(userProfile);
    }

    /**
     * 获取当前登录用户资料。
     */
    @Override
    public UserProfileBO getCurrentProfile() {
        long start = System.currentTimeMillis();
        Long userId = requireUserId();
        log.debug("[UserService.getCurrentProfile][开始] userId={}", userId);

        UserProfileBO profileBO = userConverter.toProfileBO(
                userProfileMapper.selectById(userId));
        if (profileBO == null) {
            log.warn("[UserService.getCurrentProfile][用户不存在] userId={}", userId);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        log.debug("[UserService.getCurrentProfile][完成] userId={}, costMs={}", userId, System.currentTimeMillis() - start);
        return profileBO;
    }

    @Override
    public UserProfileBO getUserProfile(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }
        UserProfileBO profileBO = userConverter.toProfileBO(userProfileMapper.selectById(userId));
        if (profileBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return profileBO;
    }

    @Override
    public AdminUserPageBO pageAdminUsers(AdminUserQueryBO query) {
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        String sort = query.getSort() == null ? "latest-login" : query.getSort();
        Long lastId = AdminIdCursorCodec.decode(query.getCursor(), sort);
        List<AdminUserListRow> rows = userProfileMapper.selectAdminUserPage(
                query.getStatus(),
                query.getDeactivated(),
                query.getRole(),
                query.getKeyword(),
                parseId(query.getKeyword()),
                sort,
                lastId,
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<AdminUserListBO> items = rows.stream().map(this::toAdminUserListBO).toList();
        AdminUserSummaryRow summaryRow = userProfileMapper.selectAdminUserSummary();
        AdminUserSummaryBO summary = AdminUserSummaryBO.builder()
                .total(summaryRow == null ? 0L : summaryRow.getTotal())
                .active(summaryRow == null ? 0L : summaryRow.getActive())
                .muted(summaryRow == null ? 0L : summaryRow.getMuted())
                .banned(summaryRow == null ? 0L : summaryRow.getBanned())
                .operators(summaryRow == null ? 0L : summaryRow.getOperators())
                .deactivated(summaryRow == null ? 0L : summaryRow.getDeactivated())
                .build();
        return AdminUserPageBO.builder()
                .items(items)
                .summary(summary)
                .hasMore(hasMore)
                .nextCursor(items.isEmpty()
                        ? null
                        : AdminIdCursorCodec.encode(sort, items.get(items.size() - 1).getId()))
                .pageSize(pageSize)
                .build();
    }

    @Override
    public AdminUserDetailBO getAdminUserDetail(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }
        AdminUserDetailRow row = userProfileMapper.selectAdminUserDetail(userId);
        if (row == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return AdminUserDetailBO.builder()
                .id(row.getId())
                .nickname(row.getNickname())
                .avatarUrl(row.getAvatarUrl())
                .bio(row.getBio())
                .gender(row.getGender())
                .openid(row.getOpenid())
                .unionidLinked(row.getUnionid() != null && !row.getUnionid().isBlank())
                .role(UserRole.fromCode(row.getRole()))
                .status(UserStatus.fromCode(row.getStatus()))
                .deactivated(row.getDeletedAt() != null)
                .restrictionReason(row.getBanReason())
                .restrictionExpiresAt(row.getBanExpireAt())
                .agreementVersion(row.getAgreementVersion())
                .agreementAgreedAt(row.getAgreementAgreedAt())
                .lastLoginAt(row.getLastLoginAt())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .deactivatedAt(row.getDeletedAt())
                .build();
    }

    private AdminUserListBO toAdminUserListBO(AdminUserListRow row) {
        return AdminUserListBO.builder()
                .id(row.getId())
                .nickname(row.getNickname())
                .avatarUrl(row.getAvatarUrl())
                .role(UserRole.fromCode(row.getRole()))
                .status(UserStatus.fromCode(row.getStatus()))
                .deactivated(row.getDeletedAt() != null)
                .restrictionReason(row.getBanReason())
                .restrictionExpiresAt(row.getBanExpireAt())
                .agreementVersion(row.getAgreementVersion())
                .lastLoginAt(row.getLastLoginAt())
                .createdAt(row.getCreatedAt())
                .deactivatedAt(row.getDeletedAt())
                .build();
    }

    private Long parseId(String keyword) {
        if (keyword == null) {
            return null;
        }
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 获取公开展示的用户资料。
     */
    @Override
    public UserPublicProfileBO getPublicProfile(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserPublicProfileBO userProfile = loadCachedValue(
                RedisKeyConstant.userPublicProfile(userId),
                UserPublicProfileBO.class,
                RedisKeyConstant.USER_PUBLIC_PROFILE_TTL,
                () -> userProfileMapper.selectPublicProfileBOById(userId)
        );

        if (userProfile == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return userProfile;
    }

    /**
     * 获取用户简版资料。
     */
    @Override
    public UserSimpleBO getSimpleInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserSimpleBO userSimpleBO = loadCachedValue(
                RedisKeyConstant.userSimpleProfile(userId),
                UserSimpleBO.class,
                RedisKeyConstant.USER_SIMPLE_PROFILE_TTL,
                () -> userProfileMapper.selectUserSimpleBOById(userId)
        );
        if (userSimpleBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return userSimpleBO;
    }

    /**
     * 批量获取用户简要信息。
     *
     * <p>流程：
     * <ol>
     *   <li>multiGet 一次拿所有命中的缓存</li>
     *   <li>找出未命中的 userId，批量查 DB</li>
     *   <li>DB 结果 mset 批量回写缓存</li>
     *   <li>合并返回完整 Map</li>
     * </ol>
     *
     * @param userIds 用户 ID 集合（允许含重复，内部去重）
     * @return userId → UserSimpleVO 的 Map；查不到的用户不在 Map 中
     */
    @Override
    public Map<Long, UserSimpleBO> batchGetSimple(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();

        // 去重
        List<Long> distinctIds = userIds.stream().distinct().toList();

        // 1. 构建 key 列表，multiGet 一次取缓存
        List<String> keys = distinctIds.stream()
                .map(RedisKeyConstant::userSimpleProfile)
                .toList();

        Map<String, UserSimpleBO> cached = redisService.multiGet(keys, UserSimpleBO.class);

        // 2. 找出未命中的 userId
        Map<Long, UserSimpleBO> result = new HashMap<>(distinctIds.size());
        List<Long> missIds = new ArrayList<>();

        for (int i = 0; i < distinctIds.size(); i++) {
            Long userId = distinctIds.get(i);
            UserSimpleBO bo = cached.get(keys.get(i));
            if (bo != null) {
                result.put(userId, bo); // 缓存命中
            } else {
                missIds.add(userId);    // 未命中，需查 DB
            }
        }

        // 3. 批量查 DB（IN 查询，一次搞定）
        if (!missIds.isEmpty()) {
            List<UserSimpleBO> dbResults = userProfileMapper.selectSimpleBatch(missIds);

            // 4. 回写缓存 + 合并结果
            Map<String, Object> toCache = new HashMap<>(dbResults.size());
            for (UserSimpleBO bo : dbResults) {
                result.put(bo.getId(), bo);
                toCache.put(RedisKeyConstant.userSimpleProfile(bo.getId()), bo);
            }
            if (!toCache.isEmpty()) {
                redisService.multiSet(toCache, RedisKeyConstant.USER_SIMPLE_PROFILE_TTL);
            }
        }

        return result;
    }

    @Override
    public UserAuthBO getUserAuthInfo(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.USER_ID_REQUIRED);
        }

        UserAuthBO userAuthBO = loadCachedValue(
                RedisKeyConstant.userAuthProfile(userId),
                UserAuthBO.class,
                RedisKeyConstant.USER_AUTH_PROFILE_TTL,
                () -> toUserAuthBO(userProfileMapper.selectUserAuthRowByUserId(userId))
        );
        if (userAuthBO == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return normalizeExpiredRestriction(userAuthBO);
    }

    @Override
    public void assertLoginAllowed(Long userId) {
        if (getUserAuthInfo(userId).getStatus() == UserStatus.BANNED) {
            throw new BusinessException(ResultCode.USER_BANNED);
        }
    }

    @Override
    public void assertContentCreationAllowed(Long userId) {
        UserStatus status = getUserAuthInfo(userId).getStatus();
        if (status == UserStatus.BANNED) {
            throw new BusinessException(ResultCode.USER_BANNED);
        }
        if (status == UserStatus.MUTED) {
            throw new BusinessException(ResultCode.USER_MUTED);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void muteUser(AdminUserRestrictionBO command) {
        changeRestriction(command, UserStatus.MUTED, Set.of(UserStatus.NORMAL));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void banUser(AdminUserRestrictionBO command) {
        changeRestriction(command, UserStatus.BANNED, Set.of(UserStatus.NORMAL, UserStatus.MUTED));
        afterCommit(() -> {
            try {
                StpUtil.kickout(command.getTargetUserId());
            } catch (RuntimeException e) {
                log.error("[UserService.banUser][会话下线失败] targetUserId={}", command.getTargetUserId(), e);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreUser(AdminUserRestrictionBO command) {
        validateRestrictionCommand(command);
        UserProfile target = requireManageableUser(command);
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
                        .eq(UserProfile::getRole, UserRole.USER.getCode())
                        .in(UserProfile::getStatus,
                                UserStatus.MUTED.getCode(), UserStatus.BANNED.getCode())
                        .isNull(UserProfile::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户状态已变化，请刷新后重试");
        }
        evictUserProfileCache(target.getId());
        log.info("[UserService.restoreUser][完成] operatorId={}, targetUserId={}",
                command.getOperatorId(), target.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeUserRole(AdminUserRoleChangeBO command) {
        validateRoleChangeCommand(command);
        if (Objects.equals(command.getOperatorId(), command.getTargetUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能修改当前管理员自己的角色");
        }

        UserProfile target = userProfileMapper.selectById(command.getTargetUserId());
        if (target == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (UserStatus.fromCode(target.getStatus()) != UserStatus.NORMAL) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "受限账号恢复正常后才能修改角色");
        }
        UserRole previousRole = UserRole.fromCode(target.getRole());
        if (previousRole == command.getTargetRole()) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户已经是目标角色");
        }
        if (previousRole == UserRole.ADMIN && command.getTargetRole() != UserRole.ADMIN) {
            List<Long> adminIds = userProfileMapper.selectAdminIdsForUpdate();
            if (adminIds.size() <= 1) {
                throw new BusinessException(ResultCode.DATA_CONFLICT, "不能移除最后一个管理员");
            }
        }

        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getRole, command.getTargetRole().getCode())
                        .eq(UserProfile::getId, target.getId())
                        .eq(UserProfile::getRole, previousRole.getCode())
                        .isNull(UserProfile::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户角色已变化，请刷新后重试");
        }

        adminOperationLogService.recordUserRoleChange(
                command.getOperatorId(),
                target.getId(),
                previousRole,
                command.getTargetRole(),
                command.getReason());
        evictUserProfileCache(target.getId());
        log.info("[UserService.changeUserRole][完成] operatorId={}, targetUserId={}, previousRole={}, targetRole={}",
                command.getOperatorId(), target.getId(), previousRole, command.getTargetRole());
    }

    /**
     * 获取用户的微信小程序 openid。
     */
    @Override
    public String getMiniOpenid(Long userId) {
        String openid = userProfileMapper.selectMiniOpenid(userId);
        if (openid == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return openid;
    }

    @Override
    public String getOfficialAccountOpenid(Long userId) {
        if (userId == null) {
            return null;
        }
        return userProfileMapper.selectMpOpenIdById(userId);
    }

    @Override
    public Long findUserIdByOfficialAccountOpenid(String mpOpenid) {
        if (!StringUtils.hasText(mpOpenid)) {
            return null;
        }
        List<Long> userIds = userProfileMapper.selectIdsByMpOpenid(mpOpenid);
        if (userIds.size() > 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "服务号身份存在重复关联，暂不能继续");
        }
        return userIds.isEmpty() ? null : userIds.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindOfficialAccountIdentity(Long userId, String mpOpenid) {
        if (userId == null || !StringUtils.hasText(mpOpenid)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "服务号身份关联参数不完整");
        }

        String current = userProfileMapper.selectMpOpenIdById(userId);
        if (mpOpenid.equals(current)) {
            return;
        }
        if (StringUtils.hasText(current)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "当前用户已绑定其他服务号身份");
        }

        Long existingOwner = findUserIdByOfficialAccountOpenid(mpOpenid);
        if (existingOwner != null && !existingOwner.equals(userId)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "该服务号身份已关联其他用户");
        }

        try {
            if (userProfileMapper.bindMpOpenidIfUnchanged(userId, mpOpenid) != 1) {
                throw new BusinessException(ResultCode.DATA_CONFLICT, "用户身份已变化，请重新发起绑定");
            }
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "该服务号身份已关联其他用户");
        }
    }

    /**
     * 更新当前登录用户资料。
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public UserProfileBO updateCurrentProfile(UserProfileUpdateBO bo) {
        if (bo == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "请求参数不能为空");
        }

        Long userId = requireUserId();
        log.debug("[UserService.updateCurrentProfile][开始] userId={}", userId);

        if (!hasAnyProfileUpdateField(bo)) {
            throw new BusinessException(ResultCode.USER_PROFILE_EMPTY);
        }

        UserProfile existingProfile = userProfileMapper.selectById(userId);
        if (existingProfile == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        assertProfileAllowed(existingProfile);
        validateProfileFields(bo);
        if (!profileWritesEnabled) throw ProfileSafetyCheckService.unavailable();
        List<String> removedObjectKeys = normalizeProfileMediaUpdate(bo, existingProfile);
        List<String> newObjectKeys = new ArrayList<>(2);
        collectNewObjectKey(newObjectKeys, existingProfile.getAvatarObjectKey(), bo.getAvatarObjectKey());
        collectNewObjectKey(newObjectKeys, existingProfile.getBackgroundObjectKey(), bo.getBackgroundObjectKey());
        UserProfile profile = userConverter.toEntity(bo);
        if (bo.getAvatarObjectKey() != null && !Objects.equals(bo.getAvatarObjectKey(), existingProfile.getAvatarObjectKey())) {
            var snapshot = prepareProfileSnapshot(bo.getAvatarObjectKey(), "/avatarObjectKey");
            profile.setAvatarSnapshotKey(snapshot.objectKey());
            profile.setAvatarUrl(snapshot.url());
            newObjectKeys.add(snapshot.objectKey());
            if (existingProfile.getAvatarSnapshotKey() != null) removedObjectKeys.add(existingProfile.getAvatarSnapshotKey());
        } else {
            profile.setAvatarSnapshotKey(existingProfile.getAvatarSnapshotKey());
            if (bo.getAvatarObjectKey() != null) profile.setAvatarUrl(existingProfile.getAvatarUrl());
        }
        if (bo.getBackgroundObjectKey() != null && !Objects.equals(bo.getBackgroundObjectKey(), existingProfile.getBackgroundObjectKey())) {
            var snapshot = prepareProfileSnapshot(bo.getBackgroundObjectKey(), "/backgroundObjectKey");
            profile.setBackgroundSnapshotKey(snapshot.objectKey());
            profile.setBackgroundUrl(snapshot.url());
            newObjectKeys.add(snapshot.objectKey());
            if (existingProfile.getBackgroundSnapshotKey() != null) removedObjectKeys.add(existingProfile.getBackgroundSnapshotKey());
        } else {
            profile.setBackgroundSnapshotKey(existingProfile.getBackgroundSnapshotKey());
            if (bo.getBackgroundObjectKey() != null) profile.setBackgroundUrl(existingProfile.getBackgroundUrl());
        }
        String openid = (bo.getNickname() != null || bo.getBio() != null) ? getMiniOpenid(userId) : null;
        profileSafety.checkText(openid, bo.getNickname());
        profileSafety.checkText(openid, bo.getBio());
        if (bo.getAvatarObjectKey() != null && !Objects.equals(profile.getAvatarSnapshotKey(), existingProfile.getAvatarSnapshotKey())) {
            profileSafety.checkMedia(userId, AuditScene.USER_AVATAR, profile.getAvatarUrl());
        }
        if (bo.getBackgroundObjectKey() != null && !Objects.equals(profile.getBackgroundSnapshotKey(), existingProfile.getBackgroundSnapshotKey())) {
            profileSafety.checkMedia(userId, AuditScene.USER_BACKGROUND, profile.getBackgroundUrl());
        }

        // No candidate has been written. Lock only during validation and final local commit.
        return new TransactionTemplate(transactionManager).execute(status -> {
            UserProfile current = userProfileMapper.selectProfileForUpdate(userId);
            if (current == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);
            assertProfileAllowed(current);
            if (!Objects.equals(current.getProfileRevision(), existingProfile.getProfileRevision())) {
                throw ProfileSafetyCheckService.unavailable();
            }
            try {
                mediaService.bindPendingUploads(TargetType.USER, userId, newObjectKeys);
            } catch (BusinessException invalidReference) {
                throw invalidProfile(bo.getAvatarObjectKey() != null ? "/avatarObjectKey" : "/backgroundObjectKey",
                        "INVALID_REFERENCE", "媒体引用不可用");
            }
            if (userProfileMapper.updateCheckedProfile(userId, current.getProfileRevision(), profile) != 1) {
                throw ProfileSafetyCheckService.unavailable();
            }
            afterCommit(() -> {
                try {
                    evictUserProfileCache(userId);
                } catch (RuntimeException failure) {
                    log.error("[UserService.updateCurrentProfile] 缓存失效失败，等待 TTL，userId={}", userId, failure);
                }
            });
            UserProfile saved = requireProfile(userId);
            Set<String> retainedKeys = new HashSet<>(Arrays.asList(saved.getAvatarObjectKey(), saved.getBackgroundObjectKey(),
                    saved.getAvatarSnapshotKey(), saved.getBackgroundSnapshotKey()));
            mediaService.deleteObjectsAfterCommit(removedObjectKeys.stream().filter(key -> !retainedKeys.contains(key)).toList(),
                    TargetType.USER, userId);
            return userConverter.toProfileBO(saved);
        });
    }

    private ProfileMediaSnapshotBO prepareProfileSnapshot(String key, String pointer) {
        try {
            return mediaService.prepareProfileSnapshot(key);
        } catch (BusinessException invalid) {
            throw invalidProfile(pointer, "INVALID_REFERENCE", "媒体引用不可用");
        } catch (ExternalServiceException failure) {
            var problem = ProfileSafetyCheckService.unavailable();
            problem.initCause(failure);
            throw problem;
        }
    }

    private void validateProfileFields(UserProfileUpdateBO command) {
        validateProfileString(command.getNickname(), "/nickname", 10, true);
        validateProfileString(command.getBio(), "/bio", 200, false);
        validateProfileString(command.getAvatarObjectKey(), "/avatarObjectKey", 512, true);
        validateProfileString(command.getBackgroundObjectKey(), "/backgroundObjectKey", 512, true);
    }

    private void validateProfileString(String value, String pointer, int max, boolean nonBlank) {
        if (value != null && ((nonBlank && value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)))
                || value.codePointCount(0, value.length()) > max)) {
            throw invalidProfile(pointer, "INVALID", "字段为空白或长度超限");
        }
    }

    private ContractProblemException invalidProfile(String pointer, String code, String detail) {
        return ContractProblemException.validation(
                new ContractProblemException.Violation("body", pointer, code, detail));
    }

    /**
     * 当前登录用户同意协议。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void agreeCurrentAgreement(String version) {
        Long userId = requireUserId();
        log.debug("[UserService.agreeCurrentAgreement][开始] userId={}, version={}", userId, version);
        if (!StringUtils.hasText(version)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "协议版本不能为空");
        }

        UserAgreement userAgreement = userAgreementMapper.selectById(userId);
        if (userAgreement == null) {
            userAgreement = UserAgreement.builder()
                    .userId(userId)
                    .version(version)
                    .build();
            userAgreementMapper.insert(userAgreement);
        } else {
            userAgreement.setVersion(version);
            userAgreement.setAgreedAt(LocalDateTime.now());
            userAgreementMapper.updateById(userAgreement);
        }

        redisService.delete(RedisKeyConstant.userAgreement(userId));
        log.debug("[UserService.agreeCurrentAgreement][完成] userId={}, version={}", userId, version);
    }

    /**
     * 查询当前登录用户协议记录。
     */
    @Override
    public UserAgreement getCurrentAgreement() {
        Long userId = requireUserId();
        return loadCachedValue(
                RedisKeyConstant.userAgreement(userId),
                UserAgreement.class,
                RedisKeyConstant.USER_AGREEMENT_TTL,
                () -> userAgreementMapper.selectById(userId)
        );
    }

    private void changeRestriction(
            AdminUserRestrictionBO command,
            UserStatus targetStatus,
            Set<UserStatus> allowedCurrentStatuses) {
        validateRestrictionCommand(command);
        UserProfile target = requireManageableUser(command);
        UserStatus currentStatus = getUserAuthInfo(target.getId()).getStatus();
        if (!allowedCurrentStatuses.contains(currentStatus)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户当前状态不允许此操作");
        }

        LocalDateTime expireAt = command.getDurationDays() == null
                ? null
                : LocalDateTime.now().plusDays(command.getDurationDays());
        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getStatus, targetStatus.getCode())
                        .set(UserProfile::getBanReason, command.getReason())
                        .set(UserProfile::getBanExpireAt, expireAt)
                        .eq(UserProfile::getId, target.getId())
                        .eq(UserProfile::getRole, UserRole.USER.getCode())
                        .eq(UserProfile::getStatus, currentStatus.getCode())
                        .isNull(UserProfile::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "用户状态已变化，请刷新后重试");
        }
        evictUserProfileCache(target.getId());
        log.info("[UserService.changeRestriction][完成] operatorId={}, targetUserId={}, status={}, expireAt={}",
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
        if (!Objects.equals(target.getRole(), UserRole.USER.getCode())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前版本仅允许处置普通用户");
        }
        return target;
    }

    private UserAuthBO normalizeExpiredRestriction(UserAuthBO auth) {
        LocalDateTime expireAt = auth.getBanExpireAt();
        if (auth.getStatus() == UserStatus.NORMAL
                || expireAt == null
                || expireAt.isAfter(LocalDateTime.now())) {
            return auth;
        }

        int updated = userProfileMapper.update(null,
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getStatus, UserStatus.NORMAL.getCode())
                        .set(UserProfile::getBanReason, null)
                        .set(UserProfile::getBanExpireAt, null)
                        .eq(UserProfile::getId, auth.getId())
                        .eq(UserProfile::getStatus, auth.getStatus().getCode())
                        .le(UserProfile::getBanExpireAt, LocalDateTime.now())
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
        return UserAuthBO.builder()
                .id(row.getId())
                .role(UserRole.fromCode(row.getRole()))
                .status(UserStatus.fromCode(row.getStatus()))
                .banReason(row.getBanReason())
                .banExpireAt(row.getBanExpireAt())
                .lastLoginAt(row.getLastLoginAt())
                .build();
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

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private <T> T loadCachedValue(String key, Class<T> clazz, Duration ttl, Supplier<T> dbLoader) {
        // 读取缓存
        T cachedRaw = redisService.get(key, clazz);

        if (cachedRaw != null) {
            return cachedRaw;
        }

        // 读取数据库
        T dbValue = dbLoader.get();

        // 写入缓存
        redisService.set(key, dbValue, ttl);
        return dbValue;
    }

    @Override
    public List<Long> listAllUserIds(long lastId, int limit) {
        return userProfileMapper.selectObjs(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getId)
                        .gt(lastId > 0, UserProfile::getId, lastId)
                        .orderByAsc(UserProfile::getId)
                        .last(" LIMIT " + limit)
        );
    }

    private boolean hasAnyProfileUpdateField(UserProfileUpdateBO bo) {
        return bo.getNickname() != null
                || bo.getAvatarUrl() != null
                || bo.getAvatarObjectKey() != null
                || bo.getBackgroundUrl() != null
                || bo.getBackgroundObjectKey() != null
                || bo.getBio() != null
                || bo.getGender() != null;
    }

    private List<String> normalizeProfileMediaUpdate(UserProfileUpdateBO bo, UserProfile existingProfile) {
        List<String> removedObjectKeys = new ArrayList<>(2);

        if (bo.getAvatarObjectKey() != null) {
            if (!StringUtils.hasText(bo.getAvatarObjectKey())) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "头像 objectKey 不能为空");
            }
            String objectKey = bo.getAvatarObjectKey().trim();
            bo.setAvatarObjectKey(objectKey);
            bo.setAvatarUrl(resolveProfileReference(objectKey, "/avatarObjectKey"));
            collectReplacedObjectKey(removedObjectKeys, existingProfile.getAvatarObjectKey(), objectKey);
        } else if (bo.getAvatarUrl() != null
                && !Objects.equals(bo.getAvatarUrl(), existingProfile.getAvatarUrl())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "更新头像必须提交 objectKey");
        }

        if (bo.getBackgroundObjectKey() != null) {
            if (!StringUtils.hasText(bo.getBackgroundObjectKey())) {
                throw new BusinessException(ResultCode.INVALID_OPERATION, "背景图 objectKey 不能为空");
            }
            String objectKey = bo.getBackgroundObjectKey().trim();
            bo.setBackgroundObjectKey(objectKey);
            bo.setBackgroundUrl(resolveProfileReference(objectKey, "/backgroundObjectKey"));
            collectReplacedObjectKey(removedObjectKeys, existingProfile.getBackgroundObjectKey(), objectKey);
        } else if (bo.getBackgroundUrl() != null
                && !Objects.equals(bo.getBackgroundUrl(), existingProfile.getBackgroundUrl())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "更新背景图必须提交 objectKey");
        }

        return removedObjectKeys;
    }

    private void collectReplacedObjectKey(List<String> removed, String previousKey, String nextKey) {
        if (StringUtils.hasText(previousKey) && !Objects.equals(previousKey, nextKey)) {
            removed.add(previousKey);
        }
    }

    private String resolveProfileReference(String objectKey, String pointer) {
        try {
            return mediaService.resolveOwnedUploadUrl(TargetType.USER, objectKey);
        } catch (BusinessException invalidReference) {
            throw invalidProfile(pointer, "INVALID_REFERENCE", "媒体引用不可用");
        }
    }

    private void collectNewObjectKey(List<String> added, String previousKey, String nextKey) {
        if (StringUtils.hasText(nextKey) && !Objects.equals(previousKey, nextKey)) {
            added.add(nextKey);
        }
    }

    private void evictUserProfileCache(Long userId) {
        redisService.delete(RedisKeyConstant.userAuthProfile(userId));
        redisService.delete(RedisKeyConstant.userPublicProfile(userId));
        redisService.delete(RedisKeyConstant.userSimpleProfile(userId));
    }
}
