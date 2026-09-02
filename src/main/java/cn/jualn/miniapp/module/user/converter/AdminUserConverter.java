package cn.jualn.miniapp.module.user.converter;

import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.enums.UserStatus;
import cn.jualn.miniapp.module.user.bo.AdminUserDetailBO;
import cn.jualn.miniapp.module.user.bo.AdminUserListBO;
import cn.jualn.miniapp.module.user.bo.AdminUserPageBO;
import cn.jualn.miniapp.module.user.bo.AdminUserQueryBO;
import cn.jualn.miniapp.module.user.bo.AdminUserRestrictionBO;
import cn.jualn.miniapp.module.user.bo.AdminUserRoleChangeBO;
import cn.jualn.miniapp.module.user.dto.admin.AdminUserPageQuery;
import cn.jualn.miniapp.module.user.dto.admin.AdminUserRoleUpdateRequest;
import cn.jualn.miniapp.module.user.dto.admin.AdminUserRestrictionRequest;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserDetailVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserListVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserPageVO;
import cn.jualn.miniapp.module.user.vo.admin.AdminUserSummaryVO;
import org.springframework.stereotype.Component;

@Component
public class AdminUserConverter {

    public AdminUserRestrictionBO toRestrictionBO(
            Long operatorId,
            Long targetUserId,
            AdminUserRestrictionRequest request) {
        return AdminUserRestrictionBO.builder()
                .operatorId(operatorId)
                .targetUserId(targetUserId)
                .reason(request.getReason().trim())
                .durationDays(request.getDurationDays())
                .build();
    }

    public AdminUserRoleChangeBO toRoleChangeBO(
            Long operatorId,
            Long targetUserId,
            AdminUserRoleUpdateRequest request) {
        return AdminUserRoleChangeBO.builder()
                .operatorId(operatorId)
                .targetUserId(targetUserId)
                .targetRole(toRequiredRole(request.getRole()))
                .reason(request.getReason().trim())
                .build();
    }

    public AdminUserQueryBO toQueryBO(AdminUserPageQuery query) {
        return AdminUserQueryBO.builder()
                .keyword(trimToNull(query.getKeyword()))
                .status(toStatus(query.getStatus()))
                .deactivated("deactivated".equals(query.getStatus()))
                .role(toRole(query.getRole()))
                .sort(query.getSort())
                .cursor(query.getCursor())
                .pageSize(query.getPageSize())
                .build();
    }

    public AdminUserPageVO toPageVO(AdminUserPageBO page) {
        return AdminUserPageVO.builder()
                .items(page.getItems().stream().map(this::toListVO).toList())
                .summary(AdminUserSummaryVO.builder()
                        .total(page.getSummary().getTotal())
                        .active(page.getSummary().getActive())
                        .muted(page.getSummary().getMuted())
                        .banned(page.getSummary().getBanned())
                        .operators(page.getSummary().getOperators())
                        .deactivated(page.getSummary().getDeactivated())
                        .build())
                .hasMore(page.getHasMore())
                .nextCursor(page.getNextCursor())
                .pageSize(page.getPageSize())
                .build();
    }

    public AdminUserDetailVO toDetailVO(AdminUserDetailBO detail) {
        return AdminUserDetailVO.builder()
                .id(detail.getId().toString())
                .nickname(detail.getNickname())
                .avatarUrl(detail.getAvatarUrl())
                .bio(detail.getBio())
                .gender(toGender(detail.getGender()))
                .openidMasked(maskIdentifier(detail.getOpenid()))
                .unionidLinked(detail.getUnionidLinked())
                .role(toRoleCode(detail.getRole()))
                .status(toStatusCode(detail.getStatus(), detail.getDeactivated()))
                .restrictionReason(detail.getRestrictionReason())
                .restrictionExpiresAt(detail.getRestrictionExpiresAt())
                .agreement(AdminUserDetailVO.Agreement.builder()
                        .version(detail.getAgreementVersion())
                        .agreedAt(detail.getAgreementAgreedAt())
                        .build())
                .lastLoginAt(detail.getLastLoginAt())
                .createdAt(detail.getCreatedAt())
                .updatedAt(detail.getUpdatedAt())
                .deactivatedAt(detail.getDeactivatedAt())
                .build();
    }

    private AdminUserListVO toListVO(AdminUserListBO item) {
        return AdminUserListVO.builder()
                .id(item.getId().toString())
                .nickname(item.getNickname())
                .avatarUrl(item.getAvatarUrl())
                .role(toRoleCode(item.getRole()))
                .status(toStatusCode(item.getStatus(), item.getDeactivated()))
                .restrictionReason(item.getRestrictionReason())
                .restrictionExpiresAt(item.getRestrictionExpiresAt())
                .agreementVersion(item.getAgreementVersion())
                .lastLoginAt(item.getLastLoginAt())
                .createdAt(item.getCreatedAt())
                .deactivatedAt(item.getDeactivatedAt())
                .build();
    }

    private Integer toStatus(String status) {
        if (status == null || "deactivated".equals(status)) {
            return null;
        }
        return switch (status) {
            case "normal" -> UserStatus.NORMAL.getCode();
            case "muted" -> UserStatus.MUTED.getCode();
            case "banned" -> UserStatus.BANNED.getCode();
            default -> null;
        };
    }

    private Integer toRole(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "user" -> UserRole.USER.getCode();
            case "operator" -> UserRole.OPR.getCode();
            case "admin" -> UserRole.ADMIN.getCode();
            default -> null;
        };
    }

    private UserRole toRequiredRole(String role) {
        return switch (role) {
            case "user" -> UserRole.USER;
            case "operator" -> UserRole.OPR;
            case "admin" -> UserRole.ADMIN;
            default -> throw new IllegalArgumentException("不支持的用户角色");
        };
    }

    private String toRoleCode(UserRole role) {
        return switch (role) {
            case USER -> "user";
            case OPR -> "operator";
            case ADMIN -> "admin";
        };
    }

    private String toStatusCode(UserStatus status, Boolean deactivated) {
        if (Boolean.TRUE.equals(deactivated)) {
            return "deactivated";
        }
        return switch (status) {
            case NORMAL -> "normal";
            case MUTED -> "muted";
            case BANNED -> "banned";
        };
    }

    private String toGender(Integer gender) {
        if (gender == null || gender == 0) {
            return "unknown";
        }
        return gender == 1 ? "male" : "female";
    }

    private String maskIdentifier(String value) {
        if (value == null || value.length() <= 8) {
            return value == null ? null : "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
