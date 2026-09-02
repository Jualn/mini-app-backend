package cn.jualn.miniapp.module.report.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminReportCaseQueryBO {
    private String keyword;
    private String status;
    private String targetType;
    private String reason;
    private String cursor;
    private Integer pageSize;
}
