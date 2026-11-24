package cn.jualn.miniapp.module.officialcontent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import cn.jualn.miniapp.module.officialcontent.enums.OfficialContentCategory;

@Data
public class OfficialContentPageQuery {
    private OfficialContentCategory category;
    @Size(max = 256) private String cursor;
    @NotNull @Min(1) @Max(50) private Integer pageSize = 20;
}
