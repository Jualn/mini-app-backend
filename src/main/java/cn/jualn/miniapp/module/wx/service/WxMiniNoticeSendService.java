package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageResponse;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult;
import org.springframework.web.reactive.function.client.WebClientException;
import cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot;
import cn.jualn.miniapp.module.wx.notice.WxNoticeDeliveryAdapter;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRegistry;
import cn.jualn.miniapp.module.wx.notice.WxNotificationTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WxMiniNoticeSendService implements WxNoticeDeliveryAdapter {
    public static final String CHANNEL = "WECHAT_MINI_PROGRAM";
    private final WxClient wxClient;
    private final WxProperties properties;
    private final UserWechatIdentityService users;
    private final WxNotificationTemplateRegistry templates;
    private final WxNotificationTemplateRenderer renderer;

    @Override
    public String channel() {
        return CHANNEL;
    }

    public boolean isTemplateConfigured(NotifyType type) {
        if (!isChannelEnabled() || type == null || properties.getMa() == null
                || (!StringUtils.hasText(properties.getMa().getAppId()) || !StringUtils.hasText(properties.getMa().getAppSecret()))) return false;
        var template = templates.resolve(type.name(), CHANNEL);
        return template != null && template.enabled();
    }

    @Override
    public boolean isChannelEnabled() {
        return templates.isChannelEnabled(CHANNEL);
    }

    public boolean isDeliveryAvailable(NotifyType type) {
        return isTemplateConfigured(type);
    }

    public WxDeliveryTemplateSnapshot prepareDelivery(NotifyType type, Map<String, Object> frozen) {
        if (!isTemplateConfigured(type)) throw unavailable();
        var template = templates.resolve(type.name(), CHANNEL, frozen);
        if (template == null) throw unavailable();
        var snapshot = renderer.renderTemplate(templates.environment(), template, frozen);
        return snapshot.withAccounts(properties.getMa().getAppId(), null);
    }

    @Override
    public WxDeliveryTemplateSnapshot prepare(NotifyType type, Map<String, Object> frozen) {
        return prepareDelivery(type, frozen);
    }

    @Override
    public boolean isFrozenAvailable(WxDeliveryTemplateSnapshot snapshot, NotifyType type) {
        return isFrozenDeliveryAvailable(snapshot);
    }

    @Override
    public WxNoticeSendResult deliver(Long userId, WxDeliveryTemplateSnapshot snapshot,
                                      String notificationId, Runnable beforeProviderCall) {
        try {
            return WxNoticeSendResult.mini(sendFrozen(userId, snapshot, notificationId, beforeProviderCall));
        } catch (ExternalServiceException failure) {
            return WxNoticeSendResult.miniFailure(failure);
        } catch (WebClientException uncertain) {
            return WxNoticeSendResult.unknown();
        }
    }

    public boolean isFrozenDeliveryAvailable(WxDeliveryTemplateSnapshot snapshot) {
        return isChannelEnabled() && snapshot != null && snapshot.schemaVersion() == 1
                && CHANNEL.equals(snapshot.channel()) && "ma-primary".equals(snapshot.accountRef())
                && "MINI_PROGRAM_SUBSCRIBE".equals(snapshot.product())
                && snapshot.bindingVersion() > 0 && snapshot.definitionVersion() > 0
                && StringUtils.hasText(snapshot.templateId()) && StringUtils.hasText(snapshot.accountAppId())
                && properties.getMa() != null && Objects.equals(properties.getMa().getAppId(), snapshot.accountAppId())
                && templates.environment().equals(snapshot.environment())
                && snapshot.miniProgramState() != null
                && Set.of("developer", "trial", "formal").contains(snapshot.miniProgramState())
                && templates.isSnapshotEnabled(snapshot);
    }

    public MiniSubscribeMessageResponse sendFrozen(Long userId, WxDeliveryTemplateSnapshot snapshot,
                                                   String notificationId, Runnable beforeProviderCall) {
        if (!isFrozenDeliveryAvailable(snapshot)) throw unavailable();
        String openid;
        try {
            openid = users.getMiniOpenid(userId);
        } catch (BusinessException missingIdentity) {
            if (missingIdentity.getResultCode() != ResultCode.USER_NOT_FOUND) throw missingIdentity;
            throw new BusinessException(ResultCode.WX_OPENID_NOT_BOUND, "小程序身份缺失");
        }
        if (!StringUtils.hasText(openid))
            throw new BusinessException(ResultCode.WX_OPENID_NOT_BOUND, "小程序身份缺失");
        String path = renderer.originatingPath(snapshot.pagePath(), Map.of("notificationId", notificationId));
        if (!path.equals(snapshot.pagePath()) || snapshot.data().isEmpty())
            throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "小程序模板快照无效");
        Map<String, MiniSubscribeMessageRequest.DataItem> data = new LinkedHashMap<>();
        snapshot.data().forEach((key, value) -> data.put(key, new MiniSubscribeMessageRequest.DataItem(value)));
        var request = new MiniSubscribeMessageRequest(openid, snapshot.templateId(),
                path.startsWith("/") ? path.substring(1) : path, snapshot.miniProgramState(), data);
        beforeProviderCall.run();
        return wxClient.sendMiniSubscribeMessage(request);
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.WX_NOTICE_SUBSCRIBE_INVALID, "小程序订阅渠道或有效资格不可用");
    }
}
