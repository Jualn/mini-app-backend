package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 服务号订阅通知发送服务。
 * <p>
 * 负责：
 * 1. 根据通知类型找到模板
 * 2. 根据字段映射渲染微信 data
 * 3. 调用 WxClient 发送
 * <p>
 * 不负责：
 * 1. 判断业务是否应该发送
 * 2. 创建站内信
 * 3. 处理复杂重试策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxMpNoticeSendService {

    private final WxClient wxClient;
    private final WxProperties wxProperties;
    private final UserService userService;
    private final WxMpNoticeTemplateRegistry templateRegistry;
    private final WxMpNoticeFieldRenderer fieldRenderer;

    /**
     * 发送服务号订阅通知。
     *
     * @param userId  接收用户 ID
     * @param type    通知类型
     * @param payload 业务字段数据
     */
    public MpSubscribeMessageResponse send(
            Long userId,
            WxMpNoticeType type,
            Map<String, Object> payload
    ) {
        return send(userId, type, payload, () -> {
        });
    }

    public MpSubscribeMessageResponse send(
            Long userId,
            WxMpNoticeType type,
            Map<String, Object> payload,
            Runnable beforeProviderCall
    ) {
        WxMpNoticeTemplateProperties.Template template = templateRegistry.getRequired(type);
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE, "服务号通知模板未启用：" + type.getKey());
        }

        String mpOpenid = userService.getOfficialAccountOpenid(userId);
        if (!StringUtils.hasText(mpOpenid)) {
            throw new BusinessException(ResultCode.WX_OPENID_NOT_BOUND, "用户未绑定服务号 openid");
        }

        Map<String, MpSubscribeMessageRequest.DataItem> data =
                fieldRenderer.render(template, payload);

        String pagePath = fieldRenderer.resolvePath(template.getPagePath(), payload);
        if (payload != null && payload.get("notificationId") != null && !StringUtils.hasText(pagePath)) {
            throw new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE, "通知模板缺少可恢复原通知的站内入口");
        }
        if (payload != null && payload.get("notificationId") != null && StringUtils.hasText(pagePath)) {
            // Always preserve the originating Notification; a configured template cannot substitute another ID.
            pagePath = org.springframework.web.util.UriComponentsBuilder.fromUriString(pagePath)
                    .replaceQueryParam("notificationId", payload.get("notificationId").toString())
                    .build().encode().toUriString();
        }

        MpSubscribeMessageRequest.MiniProgram jump = null;
        if (StringUtils.hasText(pagePath)) {
            if (wxProperties.getMa() == null || !StringUtils.hasText(wxProperties.getMa().getAppId())) {
                throw new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE,
                        "服务号通知跳转缺少小程序账号配置");
            }
            jump = MpSubscribeMessageRequest.MiniProgram.builder()
                    .appId(wxProperties.getMa().getAppId()).pagePath(pagePath).build();
        }
        MpSubscribeMessageRequest request = MpSubscribeMessageRequest.builder()
                .toUser(mpOpenid)
                .templateId(template.getTemplateId())
                .miniprogram(jump)
                .data(data)
                .build();

        log.debug("发送服务号订阅通知，userId={}, type={}", userId, type.getKey());

        beforeProviderCall.run();
        MpSubscribeMessageResponse response = wxClient.sendMpSubscribeMessage(request);
        if (response == null || response.getErrcode() == null) {
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                    "服务号订阅通知响应缺少明确结果");
        }
        if (response.getErrcode() != 0) {
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                    String.valueOf(response.getErrcode()), "服务号订阅通知被微信拒绝");
        }
        return response;
    }

    /**
     * 既有服务号链路的模板就绪情况，不代表逐用户订阅许可。
     */
    public boolean isTemplateAvailable(WxMpNoticeType type) {
        return templateRegistry.isSendEnabled(type);
    }

    /** Template plus recoverable originating Notification entry; still not subscription permission. */
    public boolean isNotificationDeliveryAvailable(WxMpNoticeType type) {
        return isTemplateAvailable(type)
                && StringUtils.hasText(templateRegistry.getRequired(type).getPagePath())
                && wxProperties.getMa() != null && StringUtils.hasText(wxProperties.getMa().getAppId());
    }
}
