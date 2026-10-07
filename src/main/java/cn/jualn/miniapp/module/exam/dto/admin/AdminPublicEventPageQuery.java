package cn.jualn.miniapp.module.exam.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminPublicEventPageQuery {
    @Size(min = 1, max = 200) private String q;
    @Pattern(regexp = "DRAFT|PUBLISHED|UNPUBLISHED") private String publishStatus;
    @Pattern(regexp = "ACTIVE|ENDED|CANCELLED") private String lifecycleStatus;
    @Pattern(regexp = "-updatedAt") private String sort = "-updatedAt";
    @Min(1) private Integer page = 1;
    @Min(1) @Max(100) private Integer pageSize = 20;
}
