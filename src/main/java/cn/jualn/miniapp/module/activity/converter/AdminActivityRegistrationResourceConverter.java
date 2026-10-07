package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationContractPageBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivityRegistrationQuery;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityRegistrationPageResourceVO;
import cn.jualn.miniapp.module.activity.vo.admin.AdminActivityRegistrationResourceVO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AdminActivityRegistrationResourceConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public Integer status(AdminActivityRegistrationQuery query) {
        return status(query.getStatus());
    }

    public Integer status(String value) {
        if (value == null) return null;
        return switch (value) {
            case "SUBMITTED" -> 1;
            case "CANCELLED" -> 2;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "invalid registration status");
        };
    }

    public AdminActivityRegistrationPageResourceVO page(ActivityRegistrationContractPageBO value) {
        return new AdminActivityRegistrationPageResourceVO(value.items().stream().map(this::registration).toList(),
                value.page(), value.pageSize(), value.totalItems());
    }

    private AdminActivityRegistrationResourceVO registration(ActivityRegistrationBO value) {
        var data = Objects.requireNonNull(value.getFormData(), "registration answers");
        if (!data.isObject()) throw new IllegalStateException("Stored registration answers must be an object");
        List<AdminActivityRegistrationResourceVO.Answer> answers = new ArrayList<>();
        data.fields().forEachRemaining(entry -> answers.add(
                new AdminActivityRegistrationResourceVO.Answer(entry.getKey(), entry.getValue())));
        return new AdminActivityRegistrationResourceVO(value.getId().toString(), value.getActivityId().toString(),
                value.getUserId().toString(), Integer.valueOf(2).equals(value.getStatus()) ? "CANCELLED" : "SUBMITTED",
                Objects.requireNonNull(value.getFormVersion(), "registration form version"), answers,
                time(Objects.requireNonNull(value.getSubmittedAt(), "submitted time")),
                time(Objects.requireNonNull(value.getUpdatedAt(), "updated time")), time(value.getCancelledAt()));
    }

    private OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
}
