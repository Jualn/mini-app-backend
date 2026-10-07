package cn.jualn.miniapp.module.user.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Canonical five-field UserProfile representation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EffectiveProfileVO(String userId, String nickname, String avatarUrl,
                                 String bio, boolean isPlatformOperator) {}
