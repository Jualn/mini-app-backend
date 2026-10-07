package cn.jualn.miniapp.module.activity.vo.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminActivityRegistrationResourceVO(
        String registrationId,
        String activityId,
        String userId,
        String status,
        String formVersion,
        List<Answer> answers,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime cancelledAt) {
    public record Answer(String fieldKey, JsonNode value) {
    }
}
