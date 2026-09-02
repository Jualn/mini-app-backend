package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.audit.bo.*;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;

public interface AuditService {

    /**
     * 执行文本内容安全审核。
     *
     * <p>该方法会直接调用微信文本审核接口，完成后同步写入审核日志。</p>
     * <p>注意：这里不使用事务包裹外部微信调用，避免远程调用失败或超时导致本地数据库回滚语义混乱。</p>
     *
     * @param bo 文本审核业务对象，包含 targetType、targetId、content、scene、openid
     * @return 审核结果对象，包含通过/拒绝状态、traceId、suggest、label 等信息
     * @throws BusinessException 参数缺失、targetType 不支持或微信接口调用前的业务校验失败时抛出
     */
    AuditCheckResultBO doTextCheck(AuditTextCheckBO bo);

    /**
     * 完整处理文本审核结果，并在审核完成后触发对应业务回调。
     *
     * <p>业务回调通过 {@link AuditResultCallback} 的实现类完成，通常用于帖子、活动、考试、评论等模块的后置处理。</p>
     *
     * @param bo 文本审核业务对象
     * @throws BusinessException 当输入参数不合法或微信审核流程前置校验失败时抛出
     */
    void processTextAudit(AuditTextCheckBO bo);

    /**
     * 提交多媒体内容安全审核。
     *
     * <p>该方法只负责向微信提交异步审核任务，并记录 trace 绑定信息；最终审核结果由微信回调处理。</p>
     * <p>为提高可恢复性，trace 绑定会同时写入 Redis 与数据库待审核记录，Redis 丢失后可通过数据库兜底恢复。</p>
     *
     * @param bo 多媒体审核业务对象，包含 targetType、targetId、mediaUrl、mediaType、scene、openid
     * @return 审核结果对象。异步提交成功时 pending=true，passed 通常为 null，traceId 用于后续回调关联
     * @throws BusinessException 参数缺失、targetType 不支持或微信接口调用前的业务校验失败时抛出
     */
    AuditCheckResultBO doMediaCheck(AuditMediaCheckBO bo);

    /**
     * 处理微信多媒体审核异步回调。
     *
     * <p>该方法会先尝试从 Redis 恢复 trace 绑定，失败时再从数据库待审核记录回源。</p>
     *
     * @param message 微信多媒体审核回调消息对象，包含 appId、traceId、errCode、errMsg、result 等信息
     * @throws BusinessException 当回调消息缺失必要字段或 traceId 无法关联到任何待审核记录时抛出
     */
    void handleWxMediaCallback(WxaMediaCheckMessage message);

}
