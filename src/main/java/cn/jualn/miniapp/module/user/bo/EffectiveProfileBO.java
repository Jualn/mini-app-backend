package cn.jualn.miniapp.module.user.bo;

import lombok.Builder;
import lombok.Value;

/** Current public facts, independent of the legacy account representation. */
@Value
@Builder
public class EffectiveProfileBO {
    Long userId;
    String nickname;
    String avatarUrl;
    String backgroundUrl;
    String bio;
    boolean platformOperator;
}
