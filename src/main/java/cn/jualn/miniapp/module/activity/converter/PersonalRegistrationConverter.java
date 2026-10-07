package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.RegistrationVersionBO;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.vo.PersonalRegistrationVO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PersonalRegistrationConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final java.util.regex.Pattern ETAG = java.util.regex.Pattern.compile(
            "\"activity-registration-([1-9][0-9]*)-v([1-9][0-9]*)\"");

    public RegistrationVersionBO versionCondition(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        var match = ETAG.matcher(ifMatch.trim());
        if (!match.matches()) {
            throw ContractProblemException.preconditionFailed();
        }
        try {
            return new RegistrationVersionBO(Long.parseLong(match.group(1)), Long.parseLong(match.group(2)));
        } catch (NumberFormatException exception) {
            throw ContractProblemException.preconditionFailed();
        }
    }

    public PersonalRegistrationVO toVO(ActivityRegistrationBO value) {
        String status = switch (Objects.requireNonNull(value.getStatus(), "registration status")) {
            case 1 -> "SUBMITTED";
            case 2 -> "CANCELLED";
            default -> throw new IllegalStateException("Unsupported personal registration status");
        };
        var data = Objects.requireNonNull(value.getFormData(), "registration answers");
        if (!data.isObject()) {
            throw new IllegalStateException("Stored registration answers must be an object");
        }
        var answers = new ArrayList<PersonalRegistrationVO.Answer>();
        data.fields().forEachRemaining(entry ->
                answers.add(new PersonalRegistrationVO.Answer(entry.getKey(), entry.getValue())));
        return new PersonalRegistrationVO(value.getId().toString(), value.getActivityId().toString(),
                status, Objects.requireNonNull(value.getFormVersion(), "registration form version"),
                answers, time(Objects.requireNonNull(value.getSubmittedAt(), "submitted time")),
                time(Objects.requireNonNull(value.getUpdatedAt(), "updated time")), time(value.getCancelledAt()),
                Objects.requireNonNull(value.getCanModify(), "modification capability"),
                Objects.requireNonNull(value.getCanCancel(), "cancellation capability"));
    }

    private OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
}
