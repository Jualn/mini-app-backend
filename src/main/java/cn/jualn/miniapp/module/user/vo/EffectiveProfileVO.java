package cn.jualn.miniapp.module.user.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Canonical effective UserProfile representation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EffectiveProfileVO(String userId, String nickname, String avatarUrl,
                                 String backgroundUrl, String bio, boolean isPlatformOperator) {}
