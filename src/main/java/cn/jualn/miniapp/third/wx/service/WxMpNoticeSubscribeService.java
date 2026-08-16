package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.third.wx.dto.WxMpSubscribeResultDTO;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeTemplateRegistry;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeTemplateView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 服务号通知订阅入口结果服务。
 * <p>
 * 只记录用户完成过订阅入口操作，不把它作为后续发送成功的强依据。
 * 后续业务通知发生时，仍然直接调用微信发送接口，失败则记录发送失败日志。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxMpNoticeSubscribeService {

    private final RedisService redisService;
    private final WxMpNoticeTemplateRegistry templateRegistry;

    /**
     * 记录 H5 订阅入口结果。
     */
    public void recordResult(WxMpSubscribeResultDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getState())) {
            throw new BusinessException(ResultCode.WX_NOTICE_SUBSCRIBE_INVALID, "订阅结果参数不完整");
        }

        String userIdRaw = redisService.getString(RedisKeyConstant.wxMpSubscribeState(dto.getState()));
        if (!StringUtils.hasText(userIdRaw)) {
            throw new BusinessException(ResultCode.WX_NOTICE_SUBSCRIBE_EXPIRED, "订阅状态已过期，请重新开启");
        }

        Long userId = Long.valueOf(userIdRaw);

        if (Boolean.TRUE.equals(dto.getSuccess())) {
            log.info("服务号通知订阅入口完成，userId={}, detail={}", userId, dto.getDetail());

            // 可选：这里只更新你的业务通知开关。
            // 不建议在这里维护复杂的模板授权状态。
            // noticeSettingService.enableServiceAccountNotice(userId);

            return;
        }

        log.info("服务号通知订阅入口未完成，userId={}, error={}", userId, dto.getError());
    }

    /**
     * 给 H5 订阅页使用的模板列表。
     * 只返回可展示、已启用、有 templateId 的模板。
     */
    public List<WxMpNoticeTemplateView> listSubscribeTemplates() {
        return templateRegistry.listSubscribeVisibleTemplates();
    }
}
