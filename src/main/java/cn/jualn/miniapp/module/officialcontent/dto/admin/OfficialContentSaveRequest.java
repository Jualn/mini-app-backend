package cn.jualn.miniapp.module.officialcontent.dto.admin;

import java.util.List;
import jakarta.validation.constraints.*;
import cn.jualn.miniapp.module.officialcontent.enums.*;

public record OfficialContentSaveRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull @Size(max = 500) String summary,
        @NotBlank @Size(max = 50000) String body,
        @NotBlank @Size(max = 120) String publisher,
        @NotNull OfficialContentCategory category,
        @NotNull OfficialContentStatus publishStatus,
        @NotNull @Size(max = 20) List<@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String> imageAttachmentIds,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String videoAttachmentId) {}
