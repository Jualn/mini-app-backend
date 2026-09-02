package cn.jualn.miniapp.module.audit.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminReviewQueryBO {
    private String keyword;
    private String targetType;
    private String riskLevel;
    private String tab;
    private String sort;
    private String cursor;
    private Integer pageSize;
}
