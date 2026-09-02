package cn.jualn.miniapp.module.audit.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminReviewDecisionBO {
    private Long taskId;
    private Long operatorId;
    private String idempotencyKey;
    private String action;
    private String reasonCode;
    private String remark;
}
