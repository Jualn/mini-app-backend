package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageResponse;
import cn.jualn.miniapp.third.wx.notice.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final UserProfileMapper userProfileMapper;
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
        WxMpNoticeTemplateProperties.Template template = templateRegistry.getRequired(type);
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "服务号通知模板未启用：" + type.getKey());
        }

        UserProfile userProfile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getMpOpenid)
                        .eq(UserProfile::getId, userId)
        );

        if (userProfile == null || !StringUtils.hasText(userProfile.getMpOpenid())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户未绑定服务号 openid");
        }

        Map<String, MpSubscribeMessageRequest.DataItem> data =
                fieldRenderer.render(template, payload);

        String pagePath = fieldRenderer.resolvePath(template.getPagePath(), payload);

        MpSubscribeMessageRequest request = MpSubscribeMessageRequest.builder()
                .toUser(userProfile.getMpOpenid())
                .templateId(template.getTemplateId())
                .miniprogram(MpSubscribeMessageRequest.MiniProgram.builder()
                        .appId(wxProperties.getMa().getAppId())
                        .pagePath(pagePath)
                        .build())
                .data(data)
                .build();

        log.info("发送服务号订阅通知，userId={}, type={}, templateId={}",
                userId, type.getKey(), template.getTemplateId());

        return wxClient.sendMpSubscribeMessage(request);

        // TODO 后续确认 wxClient 是否已经处理 errcode != 0。
        // 如果没有，这里需要判断 response 并抛 BusinessException。
    }
}