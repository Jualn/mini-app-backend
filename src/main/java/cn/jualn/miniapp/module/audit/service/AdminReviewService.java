package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.bo.AdminReviewDecisionBO;
import cn.jualn.miniapp.module.audit.bo.AdminReviewDetailBO;
import cn.jualn.miniapp.module.audit.bo.AdminReviewPageBO;
import cn.jualn.miniapp.module.audit.bo.AdminReviewQueryBO;

public interface AdminReviewService {
    AdminReviewPageBO pageReviews(AdminReviewQueryBO query);

    AdminReviewDetailBO getReviewDetail(Long taskId);

    void decide(AdminReviewDecisionBO command);
}
