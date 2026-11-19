package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.wx.notice.*;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.WxNoticeSendResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Official Account application adapter. Provider owns the permission decision for each send.
 */
@Service
@RequiredArgsConstructor
public class WxMpNoticeSendService implements WxNoticeDeliveryAdapter {
    public static final String CHANNEL = "WECHAT_OFFICIAL_ACCOUNT";
    private final WxClient wxClient;
    private final WxProperties wxProperties;
    private final UserWechatIdentityService userService;
    private final WxNotificationTemplateRegistry templateRegistry;
    private final WxNotificationTemplateRenderer fieldRenderer;

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean isChannelEnabled() {
        return templateRegistry.isChannelEnabled(CHANNEL);
    }

    public boolean isTemplateAvailable(WxMpNoticeType type) {
        var template = type == null ? null : templateRegistry.resolve(type.getKey(), CHANNEL);
        return isChannelEnabled() && template != null && template.enabled();
    }

    public boolean isNotificationDeliveryAvailable(WxMpNoticeType type) {
        return type != null && configured(templateRegistry.resolve(type.getKey(), CHANNEL));
    }

    @Override
    public boolean isDeliveryAvailable(NotifyType type) {
        return type != null && configured(templateRegistry.resolve(type.name(), CHANNEL));
    }

    private boolean configured(WxEffectiveTemplate template) {
        return isChannelEnabled() && template != null && template.enabled() && accountsConfigured()
                && (template.jumpTarget() != null || StringUtils.hasText(template.pagePath()));
    }

    @Override
    public WxDeliveryTemplateSnapshot prepare(NotifyType type, Map<String, Object> frozen) {
        if (type == null) throw unavailable();
        return prepareDelivery(type.name(), null, frozen);
    }

    /**
     * Source compatibility; legacy aliases are resolved centrally by the catalog.
     */
    public WxDeliveryTemplateSnapshot prepareDelivery(String type, WxMpNoticeType legacyType, Map<String, Object> frozen) {
        var template = templateRegistry.resolve(type == null && legacyType != null ? legacyType.getKey() : type, CHANNEL);
        if (!configured(template)) throw unavailable();
        return fieldRenderer.renderTemplate(templateRegistry.environment(), template, frozen)
                .withAccounts(wxProperties.getMp().getAppId(), wxProperties.getMa().getAppId());
    }

    public boolean isBindingDisabled(NotifyType type) {
        var template = type == null ? null : templateRegistry.resolve(type.name(), CHANNEL);
        return template != null && !template.enabled();
    }

    public boolean isFrozenDeliveryAvailable(WxDeliveryTemplateSnapshot snapshot) {
        return isChannelEnabled() && snapshot != null && snapshot.schemaVersion() == 1
                && StringUtils.hasText(snapshot.accountAppId()) && StringUtils.hasText(snapshot.jumpAppId())
                && StringUtils.hasText(snapshot.templateId()) && snapshot.bindingVersion() > 0 && snapshot.definitionVersion() > 0
                && CHANNEL.equals(snapshot.channel()) && "mp-primary".equals(snapshot.accountRef())
                && "OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND".equals(snapshot.product())
                && wxProperties.getMp() != null && Objects.equals(snapshot.accountAppId(), wxProperties.getMp().getAppId())
                && wxProperties.getMa() != null && Objects.equals(snapshot.jumpAppId(), wxProperties.getMa().getAppId())
                && templateRegistry.environment().equals(snapshot.environment()) && templateRegistry.isSnapshotEnabled(snapshot);
    }

    @Override
    public boolean isFrozenAvailable(WxDeliveryTemplateSnapshot snapshot, NotifyType type) {
        return !isBindingDisabled(type) && isFrozenDeliveryAvailable(snapshot);
    }

    public MpSubscribeMessageResponse sendFrozen(Long userId, WxDeliveryTemplateSnapshot snapshot,
                                                 String notificationId, Runnable beforeProviderCall) {
        if (!isFrozenDeliveryAvailable(snapshot)) throw unavailable();
        String path = fieldRenderer.originatingPath(snapshot.pagePath(), Map.of("notificationId", notificationId));
        if (!path.equals(snapshot.pagePath()) || snapshot.data().isEmpty())
            throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "投递快照无效");
        return sendRequest(userId, snapshot.templateId(), snapshot.data(), path, snapshot.jumpAppId(), beforeProviderCall);
    }

    @Override
    public WxNoticeSendResult deliver(Long userId, WxDeliveryTemplateSnapshot snapshot,
                                      String notificationId, Runnable beforeProviderCall) {
        try {
            return WxNoticeSendResult.official(sendFrozen(userId, snapshot, notificationId, beforeProviderCall));
        } catch (ExternalServiceException failure) {
            return WxNoticeSendResult.officialFailure(failure);
        } catch (WebClientException uncertain) {
            return WxNoticeSendResult.unknown();
        }
    }

    /**
     * Existing direct callers without a Notification ID remain supported only for legacy templates.
     */
    public MpSubscribeMessageResponse send(Long userId, WxMpNoticeType type, Map<String, Object> frozen) {
        return send(userId, type, frozen, () -> {
        });
    }

    public MpSubscribeMessageResponse send(Long userId, WxMpNoticeType type, Map<String, Object> frozen, Runnable beforeProviderCall) {
        var template = type == null ? null : templateRegistry.resolve(type.getKey(), CHANNEL);
        if (!isChannelEnabled() || template == null || !template.enabled()) throw unavailable();
        if (!template.bindingRef().startsWith("legacy:")) {
            if (frozen.get("notificationId") == null)
                throw new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "绑定发送需要原通知 ID");
            return sendFrozen(userId, prepareDelivery(type.getKey(), type, frozen), frozen.get("notificationId").toString(), beforeProviderCall);
        }
        String path = fieldRenderer.resolvePath(template.pagePath(), frozen);
        if (frozen.get("notificationId") != null) {
            if (!StringUtils.hasText(path)) throw unavailable();
            path = org.springframework.web.util.UriComponentsBuilder.fromUriString(path)
                    .replaceQueryParam("notificationId", frozen.get("notificationId").toString()).build().encode().toUriString();
        }
        String jumpAppId = wxProperties.getMa() == null ? null : wxProperties.getMa().getAppId();
        if (StringUtils.hasText(path) && !StringUtils.hasText(jumpAppId)) throw unavailable();
        return sendRequest(userId, template.templateId(), fieldRenderer.renderData(template, frozen), path, jumpAppId, beforeProviderCall);
    }

    private MpSubscribeMessageResponse sendRequest(Long userId, String templateId, Map<String, String> values,
                                                   String path, String jumpAppId, Runnable beforeProviderCall) {
        String openid = userService.getOfficialAccountOpenid(userId);
        if (!StringUtils.hasText(openid)) throw new BusinessException(ResultCode.WX_OPENID_NOT_BOUND, "服务号身份缺失");
        Map<String, MpSubscribeMessageRequest.DataItem> data = new LinkedHashMap<>();
        values.forEach((key, value) -> data.put(key, MpSubscribeMessageRequest.DataItem.builder().value(value).build()));
        var jump = !StringUtils.hasText(path) ? null : MpSubscribeMessageRequest.MiniProgram.builder().appId(jumpAppId).pagePath(path).build();
        var request = MpSubscribeMessageRequest.builder().toUser(openid).templateId(templateId).data(data).miniprogram(jump).build();
        beforeProviderCall.run();
        var response = wxClient.sendMpSubscribeMessage(request);
        if (response == null || response.getErrcode() == null)
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", "微信发送结果未知");
        if (response.getErrcode() != 0)
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", response.getErrcode().toString(), "微信发送被拒绝");
        return response;
    }

    private boolean accountsConfigured() {
        return wxProperties.getMp() != null && StringUtils.hasText(wxProperties.getMp().getAppId())
                && wxProperties.getMa() != null && StringUtils.hasText(wxProperties.getMa().getAppId());
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.WX_NOTICE_TEMPLATE_UNAVAILABLE, "微信模板或账号不可用");
    }
}
