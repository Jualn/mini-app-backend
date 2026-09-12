package cn.jualn.miniapp.module.exam.dto.admin;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data public class AdminPublicEventReasonRequest {
    @NotBlank @Size(max=255) private String reason;
}
