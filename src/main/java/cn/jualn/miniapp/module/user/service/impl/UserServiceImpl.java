package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.common.web.AdminQuery;
import cn.jualn.miniapp.common.web.NameReadBO;
import cn.jualn.miniapp.common.web.NameRow;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ContractProblemException.Violation;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService;
import cn.jualn.miniapp.module.media.bo.ProfileMediaSnapshotBO;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.module.user.enums.UserStatus;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.pagination.AdminIdCursorCodec;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.user.bo.*;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.AdminUserDetailRow;
import cn.jualn.miniapp.module.user.mapper.AdminUserListRow;
import cn.jualn.miniapp.module.user.mapper.AdminUserSummaryRow;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final cn.jualn.miniapp.module.user.service.UserAccountService accounts;
    private final cn.jualn.miniapp.module.user.service.UserWechatIdentityService identities;
    private final RedisService redisService;
    private final UserConverter userConverter;
    private final MediaService mediaService;

    private final ProfileSafetyCheckService profileSafety;
    private final PlatformTransactionManager transactionManager;
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;

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
                || command.getGender() != null) {
            throw invalidProfile("/", "INVALID", "仅允许修改昵称、头像引用、背景引用和简介");
        }
        UserProfileBO saved = updateCurrentProfile(command);
        return EffectiveProfileBO.builder().userId(saved.getId()).nickname(saved.getNickname())
                .avatarUrl(StringUtils.hasText(saved.getAvatarUrl()) ? saved.getAvatarUrl() : null)
                .backgroundUrl(StringUtils.hasText(saved.getBackgroundUrl()) ? saved.getBackgroundUrl() : null)
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
                .backgroundUrl(StringUtils.hasText(profile.getBackgroundUrl()) ? profile.getBackgroundUrl() : null)
                .bio(Objects.requireNonNullElse(profile.getBio(), ""))
                .platformOperator(Objects.equals(profile.getRole(), UserRole.OPR.getCode())
                        || Objects.equals(profile.getRole(), UserRole.ADMIN.getCode())).build();
    }

    private void assertProfileAllowed(UserProfile profile) {
        if (Objects.equals(profile.getStatus(), UserStatus.BANNED.getCode())
                && (profile.getBanExpireAt() == null || profile.getBanExpireAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
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
        profileBO.setStatus(accounts.getUserAuthInfo(userId).getStatus());
        return profileBO;
    }

    @Override
    public AdminUserPageBO pageAdminUsers(AdminUserQueryBO query) {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"user:read","USER","user:read");

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
        AdminUserSummaryBO summary = adminSummary();
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
    public AdminUserSummaryBO adminSummary() {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "user:read", "USER", "user:read");
        AdminUserSummaryRow summaryRow = userProfileMapper.selectAdminUserSummary();
        return AdminUserSummaryBO.builder()
                .total(summaryRow == null ? 0L : summaryRow.getTotal())
                .active(summaryRow == null ? 0L : summaryRow.getActive())
                .muted(summaryRow == null ? 0L : summaryRow.getMuted())
                .banned(summaryRow == null ? 0L : summaryRow.getBanned())
                .operators(summaryRow == null ? 0L : summaryRow.getOperators())
                .deactivated(summaryRow == null ? 0L : summaryRow.getDeactivated())
                .build();
    }

    @Override
    public List<NameReadBO> adminUserNames(List<String> ids) {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "user:read", "USER", "user:read");
        try {
            var storedIds = ids.stream().map(AdminQuery::storedId).filter(java.util.Objects::nonNull).toList();
            var rows = storedIds.isEmpty() ? List.<NameRow>of() : userProfileMapper.selectAdminNames(storedIds);
            var names = rows.stream().collect(java.util.stream.Collectors.toMap(
                    NameRow::id, java.util.function.Function.identity()));
            return ids.stream().map(id -> {
                var row = names.get(id.toString());
                String name = row == null || row.name() == null || row.name().isBlank() ? null : row.name();
                return new NameReadBO(id.toString(), row == null ? "UNAVAILABLE" : "READY", name, null);
            }).toList();
        } catch (RuntimeException failure) {
            var problem = AdminQuery.problem(failure);
            return ids.stream().map(id -> new NameReadBO(id.toString(), "ERROR", null, problem)).toList();
        }
    }

    @Override
    public AdminUserDetailBO getAdminUserDetail(Long userId) {
        authorization.require(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),"user:read","USER","user:read");

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
        List<String> removedObjectKeys = normalizeProfileMediaUpdate(bo, existingProfile);
        List<String> newObjectKeys = new ArrayList<>(2);
        collectNewObjectKey(newObjectKeys, existingProfile.getAvatarObjectKey(), bo.getAvatarObjectKey());
        collectNewObjectKey(newObjectKeys, existingProfile.getBackgroundObjectKey(), bo.getBackgroundObjectKey());
        UserProfile profile = userConverter.toEntity(bo);
        if (bo.getAvatarObjectKey() != null && !Objects.equals(bo.getAvatarObjectKey(), existingProfile.getAvatarObjectKey())) {
            var snapshot = prepareProfileSnapshot(bo.getAvatarObjectKey(), "/avatarObjectKey");
            newObjectKeys.remove(bo.getAvatarObjectKey());
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
            newObjectKeys.remove(bo.getBackgroundObjectKey());
            profile.setBackgroundSnapshotKey(snapshot.objectKey());
            profile.setBackgroundUrl(snapshot.url());
            newObjectKeys.add(snapshot.objectKey());
            if (existingProfile.getBackgroundSnapshotKey() != null) removedObjectKeys.add(existingProfile.getBackgroundSnapshotKey());
        } else {
            profile.setBackgroundSnapshotKey(existingProfile.getBackgroundSnapshotKey());
            if (bo.getBackgroundObjectKey() != null) profile.setBackgroundUrl(existingProfile.getBackgroundUrl());
        }
        String openid = (bo.getNickname() != null || bo.getBio() != null) ? identities.getMiniOpenid(userId) : null;
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
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if(failure.getStatus()==org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) {
                var problem=ProfileSafetyCheckService.unavailable();problem.initCause(failure);throw problem;
            }
            if(failure.getStatus()==org.springframework.http.HttpStatus.BAD_REQUEST
                    || "/problems/attachment-not-ready".equals(failure.getType()))
                throw invalidProfile(pointer,"INVALID_REFERENCE","媒体引用不可用");
            throw failure;
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
