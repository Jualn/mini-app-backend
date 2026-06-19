package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.MpQrCodeCreateResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 小程序 openid 与服务号 openid 的绑定编排服务。
 * 未开通 unionid 时，通过扫码关注回调完成关联。
 *
 * @implNote 绑定 scene 与 userId 的映射暂存于 Redis，用于回调时定位小程序用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxBindService {

    private static final int DEFAULT_SCENE_EXPIRE_SECONDS = 600;

    private final WxClient wxClient;
    private final RedisService redisService;
    private final UserProfileMapper userProfileMapper;

    /**
     * 生成绑定二维码。通常由小程序端触发，展示给用户去扫码关注服务号。
     *
     * @param userId 小程序用户 ID
     * @return 绑定二维码信息（scene、ticket、展示 URL、过期时间）
     */
    public BindQrInfo createBindQr(Long userId) {
        String scene = buildScene(userId);
        redisService.set(buildSceneKey(scene), String.valueOf(userId), Duration.ofSeconds(DEFAULT_SCENE_EXPIRE_SECONDS));

        MpQrCodeCreateResponse qrCode = wxClient.createQrSceneTicket(scene, DEFAULT_SCENE_EXPIRE_SECONDS);
        String qrUrl = "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket="
                + URLEncoder.encode(qrCode.getTicket(), StandardCharsets.UTF_8);

        return BindQrInfo.builder()
                .scene(scene)
                .ticket(qrCode.getTicket())
                .qrUrl(qrUrl)
                .expireAt(LocalDateTime.now().plusSeconds(DEFAULT_SCENE_EXPIRE_SECONDS))
                .build();
    }

    /**
     * 处理扫码相关绑定回调（包括：扫码后关注 subscribe、已关注再扫码 scan）。
     *
     * @param scene 绑定场景值
     * @param mpOpenid 服务号侧用户 openid
     * @param eventType 微信事件类型（subscribe/scan）
     */
    public void handleScanBind(String scene, String mpOpenid, String eventType) {
        if (!StringUtils.hasText(scene) || !StringUtils.hasText(mpOpenid)) {
            log.warn("微信绑定回调参数不完整，scene={}, mpOpenid={}, eventType={}", scene, mpOpenid, eventType);
            return;
        }

        String userIdRaw = redisService.getString(buildSceneKey(scene));
        if (!StringUtils.hasText(userIdRaw)) {
            log.warn("微信绑定场景不存在或已过期，scene={}, mpOpenid={}, eventType={}", scene, mpOpenid, eventType);
            return;
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdRaw);
        } catch (NumberFormatException ex) {
            log.error("微信绑定场景数据异常，scene={}, userIdRaw={}", scene, userIdRaw, ex);
            return;
        }

        UserProfile userProfile = userProfileMapper.selectById(userId);
        if (userProfile == null) {
            log.warn("微信绑定用户不存在，userId={}, scene={}", userId, scene);
            return;
        }

        if (mpOpenid.equals(userProfile.getMpOpenid())) {
            log.info("微信绑定重复回调，已绑定无需更新，userId={}, mpOpenid={}", userId, mpOpenid);
            return;
        }

        userProfile.setMpOpenid(mpOpenid);
        int updated = userProfileMapper.updateById(userProfile);
        if (updated > 0) {
            log.info("微信扫码绑定成功，userId={}, mpOpenid={}, eventType={}", userId, mpOpenid, eventType);
        } else {
            log.warn("微信扫码绑定更新失败，userId={}, mpOpenid={}, eventType={}", userId, mpOpenid, eventType);
        }
    }

    /**
     * 未扫码直接关注服务号。当前仅记录日志，后续可扩展欢迎消息、打标签等逻辑。
     *
     * @param mpOpenid 服务号侧用户 openid
     */
    public void handleDirectSubscribe(String mpOpenid) {
        log.info("微信服务号直接关注事件，mpOpenid={}，未携带 scene，跳过小程序绑定", mpOpenid);
    }

    /**
     * 生成绑定场景值。
     *
     * @param userId 小程序用户 ID
     * @return scene 字符串
     */
    private String buildScene(Long userId) {
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "bind_" + userId + "_" + randomSuffix;
    }

    /**
     * 生成 scene 缓存键。
     *
     * @param scene 绑定场景值
     * @return Redis key
     */
    private String buildSceneKey(String scene) {
        return RedisKeyConstant.wxBindScene(scene);
    }

    /**
     * 绑定二维码返回对象。
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class BindQrInfo {
        private String scene;
        private String ticket;
        private String qrUrl;
        private LocalDateTime expireAt;
    }
}

