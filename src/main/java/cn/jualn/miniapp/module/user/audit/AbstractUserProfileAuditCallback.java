package cn.jualn.miniapp.module.user.audit;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.notice.data.AuditResultNoticeData;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 用户资料审核回调基类。
 *
 * <p>审核通过不处理；审核失败由具体字段 callback 实现。</p>
 */
@RequiredArgsConstructor
public abstract class AbstractUserProfileAuditCallback implements AuditResultCallback {

    protected final UserProfileMapper userProfileMapper;
    protected final RedisService redisService;
    protected final QueueProducer queueProducer;

    @Override
    public void onPass(Long userId) {
        // 轻量方案：资料已乐观写入，通过不用处理
    }

    protected void evictUserProfileCache(Long userId) {
        redisService.delete(RedisKeyConstant.userPublicProfile(userId));
        redisService.delete(RedisKeyConstant.userSimpleProfile(userId));
    }

    /**
     * 发送审核未通过通知
     *
     * @param userId 用户ID
     * @param auditObject 审核对象描述, 或者说审核的是什么东西，像用户头像
     * @param content 站内通知内容
     * @param reason 审核未通过原因
     */
    protected void sendRejectNotify(Long userId, String auditObject, String content, String reason) {
        String actualReason = StringUtils.hasText(reason) ? reason : content;

        queueProducer.send(NotifyPayload.builder()
                .receiverId(userId)
                .senderId(null)
                .type(NotifyType.AUDIT_RESULT)
                .title("资料修改未通过审核")
                .content(content)
                .wxData(new AuditResultNoticeData(
                        truncate(auditObject, 20),
                        "未通过",
                        truncate(actualReason, 20),
                        LocalDateTime.now()
                ))
                .build());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
