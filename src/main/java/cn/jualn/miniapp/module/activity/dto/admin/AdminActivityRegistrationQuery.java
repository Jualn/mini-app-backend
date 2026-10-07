package cn.jualn.miniapp.module.activity.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AdminActivityRegistrationQuery {
    @Min(1) private Integer page = 1;
    @Min(1) @Max(100) private Integer pageSize = 20;
    @Pattern(regexp = "SUBMITTED|CANCELLED") private String status;
    @Pattern(regexp = "-submittedAt") private String sort = "-submittedAt";
}
