package cn.jualn.miniapp.module.admin.auth.dto.qrlogin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class AdminQrSceneRequest {
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9_-]{32}")
    private String sceneCode;
    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isTextual()) throw ContractProblemException.validation(
                new ContractProblemException.Violation("body", "/sceneCode", "TYPE_MISMATCH", "sceneCode must be a string"));
        this.sceneCode = value.textValue();
    }
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw ContractProblemException.validation(new ContractProblemException.Violation(
                "body", "/", "UNKNOWN_PROPERTY", "Only sceneCode is accepted"));
    }
    @Override public String toString() { return "AdminQrSceneRequest[credentials redacted]"; }
}
