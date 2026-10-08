package cn.jualn.miniapp.module.user.dto.request;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

/** Presence-aware closed request; lengths are validated by the state owner. */
public final class EffectiveProfileUpdateRequest {
    private String nickname;
    private String avatarObjectKey;
    private String backgroundObjectKey;
    private String bio;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setNickname(JsonNode value) { nickname = stringValue(value, "/nickname"); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setAvatarObjectKey(JsonNode value) { avatarObjectKey = stringValue(value, "/avatarObjectKey"); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setBackgroundObjectKey(JsonNode value) { backgroundObjectKey = stringValue(value, "/backgroundObjectKey"); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setBio(JsonNode value) { bio = stringValue(value, "/bio"); }

    private String stringValue(JsonNode value, String pointer) {
        if (value == null || !value.isTextual()) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "body", pointer, "TYPE_MISMATCH", "字段必须为字符串"));
        }
        return value.textValue();
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw ContractProblemException.validation(new ContractProblemException.Violation(
                "body", "/" + name.replace("~", "~0").replace("/", "~1"),
                "UNKNOWN_PROPERTY", "不允许修改此字段"));
    }

    public UserProfileUpdateBO toCommand() {
        return UserProfileUpdateBO.builder().nickname(nickname).avatarObjectKey(avatarObjectKey)
                .backgroundObjectKey(backgroundObjectKey).bio(bio).build();
    }
}
