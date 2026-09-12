package cn.jualn.miniapp.module.exam.dto.admin;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class AdminPublicEventPageQuery {
    @Size(max=128) private String keyword;
    @Min(0) @Max(7) private Integer category;
    @Min(0) @Max(3) private Integer eventType;
    @Min(0) @Max(3) private Integer publishStatus;
    @Size(max=256) private String cursor;
    @Min(1) @Max(100) private Integer pageSize = 20;
}
