package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;

/**
 * 审核任务预占服务。
 *
 * <p>业务模块通过该入口创建待审核日志；该服务不负责分发审核结果回调。</p>
 */
public interface AuditReservationService {

    AuditReserveResultBO reserveAuditLogs(AuditReserveBO bo);
}
