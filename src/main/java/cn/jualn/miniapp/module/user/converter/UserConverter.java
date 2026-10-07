package cn.jualn.miniapp.module.user.converter;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.module.user.bo.UserProfileBO;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import cn.jualn.miniapp.module.user.bo.UserPublicProfileBO;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.dto.request.UserProfileUpdateRequest;
import cn.jualn.miniapp.module.user.entity.UserAgreement;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.vo.UserAgreementStatusVO;
import cn.jualn.miniapp.module.user.vo.UserPublicProfileVO;
import cn.jualn.miniapp.module.user.vo.UserProfileVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring")
public interface UserConverter {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "profileRevision", ignore = true)
    @Mapping(target = "avatarSnapshotKey", ignore = true)
    @Mapping(target = "backgroundSnapshotKey", ignore = true)
    @Mapping(target = "openid", ignore = true)
    @Mapping(target = "mpOpenid", ignore = true)
    @Mapping(target = "unionid", ignore = true)
    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "banReason", ignore = true)
    @Mapping(target = "banExpireAt", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    UserProfile toEntity(UserProfileUpdateBO updateBO);

    UserProfileUpdateBO toUpdateBO(UserProfileUpdateRequest updateRequest);

    @Mapping(target = "roleDesc", expression = "java(resolveRoleDesc(userProfile.getRole()))")
    @Mapping(target = "statusDesc", expression = "java(resolveStatusDesc(userProfile.getStatus()))")
    @Mapping(target = "banned", expression = "java(isBanned(userProfile.getStatus()))")
    @Mapping(target = "muted", expression = "java(isMuted(userProfile.getStatus()))")
    @Mapping(target = "capabilities", expression = "java(resolveCapabilities(userProfile.getRole(), userProfile.getStatus()))")
    UserProfileVO toVO(UserProfileBO userProfile);

    UserPublicProfileVO toPublicRespVO(UserPublicProfileBO profileBO);

    UserInfoDTO toUserInfoDTO(UserProfile userProfile);

    UserProfileBO toProfileBO(UserProfile userProfile);

    @Mapping(target = "agreed", expression = "java(true)")
    UserAgreementStatusVO toAgreementStatusRespVO(UserAgreement userAgreement);

    default UserRole resolveRole(Integer roleCode) {
        return UserRole.fromCode(roleCode);
    }

    default String resolveRoleDesc(UserRole roleCode) {
        return roleCode.getDesc();
    }

    default Integer resolveRoleCode(UserRole roleCode) {
        return roleCode.getCode();
    }

    default UserStatus resolveStatus(Integer statusCode) {
        return UserStatus.fromCode(statusCode);
    }

    default String resolveStatusDesc(UserStatus statusCode) {
        return statusCode.getDesc();
    }

    default Integer resolveStatusCode(UserStatus statusCode) {
        return statusCode.getCode();
    }

    default boolean isBanned(UserStatus status) {
        return UserStatus.BANNED == status;
    }

    default boolean isMuted(UserStatus status) {
        return UserStatus.MUTED == status;
    }

    default List<String> resolveCapabilities(UserRole role, UserStatus status) {
        if (status == UserStatus.BANNED) {
            return List.of();
        }

        List<String> capabilities = new ArrayList<>(List.of("profile:edit"));
        if (status != UserStatus.MUTED) {
            capabilities.add("post:create");
            capabilities.add("comment:create");
        }

        if (role == UserRole.OPR || role == UserRole.ADMIN) {
            capabilities.add("content:audit");
            capabilities.add("activity:manage");
        }
        if (role == UserRole.ADMIN) {
            capabilities.add("user:manage");
            capabilities.add("system:manage");
        }
        return capabilities;
    }
}
