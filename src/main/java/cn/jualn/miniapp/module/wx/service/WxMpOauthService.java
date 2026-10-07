package cn.jualn.miniapp.module.wx.service;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpOauthAccessTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 服务号 H5 网页授权编排服务。
 * <p>
 * 现在拆成两类：
 * 1. bind：小程序侧绑定服务号 openid
 * 2. subscribe：服务号菜单侧开启通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxMpOauthService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration CLAIM_TTL = Duration.ofSeconds(30);

    private static final String OAUTH_MODE_BIND_PREFIX = "bind:";
    private static final String OAUTH_MODE_SUBSCRIBE = "subscribe";
    private static final String RESOLVED_BIND_PREFIX = "resolved-bind:";
    private static final String RESOLVED_SUBSCRIBE_PREFIX = "resolved-subscribe:";
    private static final String DONE_PREFIX = "done:";

    private final WxClient wxClient;
    private final WxProperties wxProperties;
    private final RedisService redisService;
    private final UserService userService;

    /**
     * 小程序通知设置页：绑定服务号入口。
     * <p>
     * 只做绑定，不跳 wx-open-subscribe。
     */
    public String buildBindOauthUrl(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户未登录");
        }
        String state = UUID.randomUUID().toString().replace("-", "");

        redisService.setRequired(
                RedisKeyConstant.wxMpOauthState(state),
                OAUTH_MODE_BIND_PREFIX + userId,
                STATE_TTL
        );

        return buildWechatOauthUrl(state);
    }

    /**
     * 服务号菜单：开启通知入口。
     * <p>
     * 不要求小程序 token。
     * 通过服务号网页授权拿 mpOpenid，再反查 userId。
     */
    public String buildSubscribeOauthUrl() {
        String state = UUID.randomUUID().toString().replace("-", "");

        redisService.setRequired(
                RedisKeyConstant.wxMpOauthState(state),
                OAUTH_MODE_SUBSCRIBE,
                STATE_TTL
        );

        return buildWechatOauthUrl(state);
    }

    /**
     * 处理微信授权回调。
     * <p>
     * bind 模式：
     * - 绑定 mpOpenid 到 userId
     * - 跳转绑定成功页
     * <p>
     * subscribe 模式：
     * - 根据 mpOpenid 查询 userId
     * - 查不到：跳未绑定兜底页
     * - 查到：生成 subscribeState，进入 wx-open-subscribe 页
     */
    public String handleCallback(String code, String state) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(state)) {
            throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权回调参数不完整");
        }

        String stateKey = RedisKeyConstant.wxMpOauthState(state);
        String stateValue = redisService.getString(stateKey);
        if (!StringUtils.hasText(stateValue)) {
            throw new BusinessException(ResultCode.WX_OAUTH_STATE_EXPIRED, "微信授权状态已过期，请重新打开页面");
        }
        if (stateValue.startsWith(DONE_PREFIX)) {
            return stateValue.substring(DONE_PREFIX.length());
        }

        String claimToken = UUID.randomUUID().toString();
        String claimKey = RedisKeyConstant.wxMpOauthClaim(state);
        if (!redisService.setIfAbsent(claimKey, claimToken, CLAIM_TTL)) {
            throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权回调正在处理中，请稍后重试");
        }

        try {
            stateValue = redisService.getString(stateKey);
            if (!StringUtils.hasText(stateValue)) {
                throw new BusinessException(ResultCode.WX_OAUTH_STATE_EXPIRED, "微信授权状态已过期，请重新打开页面");
            }
            if (stateValue.startsWith(DONE_PREFIX)) {
                return stateValue.substring(DONE_PREFIX.length());
            }

            String resolvedState = stateValue;
            if (!isResolved(stateValue)) {
                MpOauthAccessTokenResponse oauth = wxClient.getMpOauthAccessToken(code);
                String mpOpenid = oauth.getOpenid();
                if (!StringUtils.hasText(mpOpenid)) {
                    throw new BusinessException(ResultCode.WX_API_ERROR, "微信网页授权未返回 openid");
                }
                resolvedState = resolveIdentityState(stateValue, mpOpenid);
                redisService.setRequired(stateKey, resolvedState, STATE_TTL);
            }

            String redirect = completeResolvedState(resolvedState, state);
            redisService.setRequired(stateKey, DONE_PREFIX + redirect, STATE_TTL);
            return redirect;
        } finally {
            redisService.deleteIfValueMatches(claimKey, claimToken);
        }
    }

    /**
     * 小程序通知设置页判断是否已绑定。
     */
    public Map<String, Object> getBindStatus(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户未登录");
        }
        boolean bound = StringUtils.hasText(userService.getOfficialAccountOpenid(userId));

        return Map.of(
                "bound", bound,
                "mpBind", bound
        );
    }

    /**
     * 构建微信服务号 snsapi_base 授权地址。
     */
    private String buildWechatOauthUrl(String state) {
        String callbackUrl = wxProperties.getMp().getDomain()
                + "/third/wx/mp-oauth/callback";

        String encodedRedirectUri = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + wxProperties.getMp().getAppId()
                + "&redirect_uri=" + encodedRedirectUri
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + state
                + "#wechat_redirect";
    }

    /**
     * 构建 H5 页面地址。
     */
    private String buildH5PageUrl(String mode, String state) {
        String url = wxProperties.getMp().getDomain()
                + "/h5/service-subscribe/index.html?mode="
                + URLEncoder.encode(mode, StandardCharsets.UTF_8);

        if (StringUtils.hasText(state)) {
            url += "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        }

        return url;
    }

    private boolean isResolved(String stateValue) {
        return stateValue.startsWith(RESOLVED_BIND_PREFIX)
                || stateValue.startsWith(RESOLVED_SUBSCRIBE_PREFIX);
    }

    private String resolveIdentityState(String stateValue, String mpOpenid) {
        String encodedOpenid = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mpOpenid.getBytes(StandardCharsets.UTF_8));
        if (stateValue.startsWith(OAUTH_MODE_BIND_PREFIX)) {
            String userId = stateValue.substring(OAUTH_MODE_BIND_PREFIX.length());
            try {
                Long.parseLong(userId);
            } catch (NumberFormatException invalidState) {
                throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权状态异常，请重新打开页面");
            }
            return RESOLVED_BIND_PREFIX + userId + ":" + encodedOpenid;
        }
        if (OAUTH_MODE_SUBSCRIBE.equals(stateValue)) {
            return RESOLVED_SUBSCRIBE_PREFIX + encodedOpenid;
        }
        throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权状态异常，请重新打开页面");
    }

    private String completeResolvedState(String resolvedState, String state) {
        if (resolvedState.startsWith(RESOLVED_BIND_PREFIX)) {
            String value = resolvedState.substring(RESOLVED_BIND_PREFIX.length());
            int separator = value.indexOf(':');
            if (separator <= 0) {
                throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权状态异常，请重新打开页面");
            }
            Long userId = Long.valueOf(value.substring(0, separator));
            String mpOpenid = decodeOpenid(value.substring(separator + 1));
            userService.bindOfficialAccountIdentity(userId, mpOpenid);
            return buildH5PageUrl("bind-success", null);
        }

        if (resolvedState.startsWith(RESOLVED_SUBSCRIBE_PREFIX)) {
            String mpOpenid = decodeOpenid(resolvedState.substring(RESOLVED_SUBSCRIBE_PREFIX.length()));
            Long userId = userService.findUserIdByOfficialAccountOpenid(mpOpenid);
            if (userId == null) {
                log.info("服务号开启通知失败：服务号身份未绑定小程序用户");
                return buildH5PageUrl("unbound", null);
            }
            redisService.setRequired(RedisKeyConstant.wxMpSubscribeState(state), String.valueOf(userId), STATE_TTL);
            return buildH5PageUrl("subscribe", state);
        }

        throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权状态异常，请重新打开页面");
    }

    private String decodeOpenid(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidState) {
            throw new BusinessException(ResultCode.WX_OAUTH_STATE_INVALID, "微信授权状态异常，请重新打开页面");
        }
    }
}
