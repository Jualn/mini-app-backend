package cn.jualn.miniapp.module.activity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonalRegistrationVO(
        String registrationId, String activityId, String status, String formVersion,
        List<Answer> answers, OffsetDateTime submittedAt, OffsetDateTime updatedAt,
        OffsetDateTime cancelledAt, boolean canModify, boolean canCancel) {
    public record Answer(String fieldKey, JsonNode value) {
    }
}
