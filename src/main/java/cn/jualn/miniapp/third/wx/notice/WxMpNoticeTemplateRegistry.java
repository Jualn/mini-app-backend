package cn.jualn.miniapp.third.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 服务号通知模板注册表。
 * <p>
 * 统一提供模板 ID、展示名称、是否可订阅、是否可发送等信息。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class WxMpNoticeTemplateRegistry {

    private final WxMpNoticeTemplateProperties properties;

    /**
     * 获取指定通知类型的模板配置。
     */
    public WxMpNoticeTemplateProperties.Template getRequired(WxMpNoticeType type) {
        return properties.getNoticeTemplates()
                .values()
                .stream()
                .filter(item -> Objects.equals(item.getType(), type.getKey()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ResultCode.PARAM_ERROR,
                        "未配置服务号通知模板：" + type.getKey()
                ));
    }

    /**
     * 获取 H5 订阅页可展示的模板。
     */
    public List<WxMpNoticeTemplateView> listSubscribeVisibleTemplates() {
        return properties.getNoticeTemplates()
                .values()
                .stream()
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getSubscribeVisible()))
                .filter(item -> StringUtils.hasText(item.getTemplateId()))
                .map(item -> WxMpNoticeTemplateView.builder()
                        .type(item.getType())
                        .name(item.getName())
                        .templateId(item.getTemplateId())
                        .build())
                .toList();
    }
}