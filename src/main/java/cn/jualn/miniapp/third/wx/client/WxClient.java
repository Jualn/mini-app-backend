package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 微信第三方网关客户端。
 * <p>
 * 负责封装小程序与服务号的常用 API 调用，包括：
 * 小程序登录、服务号 access_token 获取、二维码创建、用户信息查询、模板消息发送。
 * </p>
 * <p>
 * 本类仅处理第三方调用与通用错误转换，不承载业务编排逻辑。
 * </p>
 *
 * @implNote 服务号 access_token 会缓存到 Redis；当识别到 token 失效时会刷新并重试一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxClient {

    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String MP_CREATE_QR_URL = "https://api.weixin.qq.com/cgi-bin/qrcode/create";
    private static final String MP_TEMPLATE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/template/send";
    private static final String MP_USER_INFO_URL = "https://api.weixin.qq.com/cgi-bin/user/info";
    private static final String MP_OAUTH_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String MP_JSAPI_TICKET_URL = "https://api.weixin.qq.com/cgi-bin/ticket/getticket";
    private static final String MP_SUBSCRIBE_BIZ_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/bizsend";
    private static final String MINI_CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String MINI_MSG_SEC_CHECK_URL = "https://api.weixin.qq.com/wxa/msg_sec_check";
    private static final String MINI_MEDIA_CHECK_ASYNC_URL = "https://api.weixin.qq.com/wxa/media_check_async";

    private final WebClient webClient;
    private final WxProperties wxProperties;
    private final RedisService redisService;

    /**
     * 小程序登录：用 code 换取 openid / session_key。
     *
     * @param code 小程序前端调用 wx.login 获取的临时登录凭证
     * @return 小程序会话信息（含 openid、sessionKey、unionid 等）
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public MiniSessionResponse getMiniSession(String code) {
        String url = MINI_CODE2SESSION_URL
                + "?appid=" + wxProperties.getMa().getAppId()
                + "&secret=" + wxProperties.getMa().getAppSecret()
                + "&js_code=" + code
                + "&grant_type=authorization_code";
        MiniSessionResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MiniSessionResponse.class)
                .block();
        return assertWxSuccess(response, "小程序 code2session 调用失败");
    }

    /**
     * 获取服务号 access_token。
     * token 会缓存到 Redis，避免高频请求触发微信限流。
     *
     * @param forceRefresh 是否强制跳过缓存并重新向微信请求 token
     * @return 可用于服务号接口调用的 access_token
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public String getMpAccessToken(boolean forceRefresh) {
        if (!forceRefresh) {
            String cachedToken = redisService.getString(RedisKeyConstant.WX_MP_ACCESS_TOKEN);
            if (StringUtils.hasText(cachedToken)) {
                return cachedToken;
            }
        }

        String url = ACCESS_TOKEN_URL
                + "?grant_type=client_credential"
                + "&appid=" + wxProperties.getMp().getAppId()
                + "&secret=" + wxProperties.getMp().getAppSecret();
        MpAccessTokenResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpAccessTokenResponse.class)
                .block();
        MpAccessTokenResponse valid = assertWxSuccess(response, "服务号 access_token 获取失败");
//        int ttlSeconds = Math.max(60, valid.getExpiresIn() - 200);
        redisService.set(RedisKeyConstant.WX_MP_ACCESS_TOKEN,
                valid.getAccessToken(), RedisKeyConstant.WX_MP_ACCESS_TOKEN_TTL);
        return valid.getAccessToken();
    }

    /**
     * 获取小程序 access_token。
     * token 会缓存到 Redis，避免高频请求触发微信限流。
     *
     * @param forceRefresh 是否强制跳过缓存并重新向微信请求 token
     * @return 可用于小程序接口调用的 access_token
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public String getMiniAccessToken(boolean forceRefresh) {
        if (!forceRefresh) {
            String cachedToken = redisService.getString(RedisKeyConstant.WX_MINI_ACCESS_TOKEN);
            if (StringUtils.hasText(cachedToken)) {
                return cachedToken;
            }
        }

        String url = ACCESS_TOKEN_URL
                + "?grant_type=client_credential"
                + "&appid=" + wxProperties.getMa().getAppId()
                + "&secret=" + wxProperties.getMa().getAppSecret();
        MiniAccessTokenResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MiniAccessTokenResponse.class)
                .block();
        MiniAccessTokenResponse valid = assertWxSuccess(response, "小程序 access_token 获取失败");
//        int ttlSeconds = Math.max(60, valid.getExpiresIn() - 200);
        redisService.set(RedisKeyConstant.WX_MINI_ACCESS_TOKEN,
                valid.getAccessToken(), RedisKeyConstant.WX_MINI_ACCESS_TOKEN_TTL);
        return valid.getAccessToken();
    }

    /**
     * 小程序文本内容安全检测。
     *
     * @param request 文本审核请求体
     * @return 微信审核结果（含 traceId/result/detail）
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public WxMsgSecCheckResponse msgSecCheck(WxMsgSecCheckRequest request) {
        String token = getMiniAccessToken(false);
        WxMsgSecCheckResponse response = callMsgSecCheck(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信小程序 access_token 失效，刷新后重试文本审核");
            token = getMiniAccessToken(true);
            response = callMsgSecCheck(request, token);
        }
        return assertWxSuccess(response, "小程序文本内容审核失败");
    }

    /**
     * 小程序多媒体内容安全异步检测。
     *
     * @param request 多媒体审核请求体
     * @return 微信异步审核提交结果（含 traceId）
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public WxMediaCheckAsyncResponse mediaCheckAsync(WxMediaCheckAsyncRequest request) {
        String token = getMiniAccessToken(false);
        WxMediaCheckAsyncResponse response = callMediaCheckAsync(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信小程序 access_token 失效，刷新后重试多媒体审核提交");
            token = getMiniAccessToken(true);
            response = callMediaCheckAsync(request, token);
        }
        return assertWxSuccess(response, "小程序多媒体内容审核失败");
    }

    /**
     * 创建临时二维码（字符串 scene）。
     *
     * @param scene         二维码场景值（用于回调绑定或业务追踪）
     * @param expireSeconds 二维码过期秒数
     * @return 创建二维码结果（ticket、url、expireSeconds）
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public MpQrCodeCreateResponse createQrSceneTicket(String scene, int expireSeconds) {
        String token = getMpAccessToken(false);
        MpQrCodeCreateResponse response = callCreateQr(scene, expireSeconds, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试创建二维码，scene={}", scene);
            token = getMpAccessToken(true);
            response = callCreateQr(scene, expireSeconds, token);
        }
        return assertWxSuccess(response, "创建服务号二维码失败");
    }

    /**
     * 查询服务号用户信息（关注状态、昵称等）。
     *
     * @param mpOpenid 服务号侧用户 openid
     * @return 服务号用户信息
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public MpUserInfoResponse getMpUserInfo(String mpOpenid) {
        String token = getMpAccessToken(false);
        String url = MP_USER_INFO_URL
                + "?access_token=" + token
                + "&openid=" + mpOpenid
                + "&lang=zh_CN";
        MpUserInfoResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpUserInfoResponse.class)
                .block();
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试获取用户信息，mpOpenid={}", mpOpenid);
            String refreshToken = getMpAccessToken(true);
            String refreshUrl = MP_USER_INFO_URL
                    + "?access_token=" + refreshToken
                    + "&openid=" + mpOpenid
                    + "&lang=zh_CN";
            response = webClient.get()
                    .uri(refreshUrl)
                    .retrieve()
                    .bodyToMono(MpUserInfoResponse.class)
                    .block();
        }
        return assertWxSuccess(response, "获取服务号用户信息失败");
    }

    /**
     * 发送服务号模板消息。
     * 这里仅封装发送能力，业务侧可结合通知表/队列异步调用。
     *
     * @param request 模板消息请求体（接收用户、模板 ID、模板数据等）
     * @return 模板消息发送结果（含 msgid）
     * @throws BusinessException 当微信接口返回失败或响应为空时抛出
     */
    public MpTemplateMessageResponse sendMpTemplateMessage(MpTemplateMessageRequest request) {
        String token = getMpAccessToken(false);
        MpTemplateMessageResponse response = callSendTemplate(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试发送模板消息，toUser={}, templateId={}",
                    request.getToUser(), request.getTemplateId());
            String refreshToken = getMpAccessToken(true);
            response = callSendTemplate(request, refreshToken);
        }
        return assertWxSuccess(response, "发送服务号模板消息失败");
    }

    /**
     * 服务号网页授权：用 code 换取网页授权 access_token 与 openid。
     * <p>
     * 当前业务只需要 openid，用于绑定当前小程序用户与服务号用户身份。
     * </p>
     *
     * @param code 微信网页授权回调携带的 code
     * @return 网页授权结果，包含服务号 openid
     */
    public MpOauthAccessTokenResponse getMpOauthAccessToken(String code) {
        String url = MP_OAUTH_ACCESS_TOKEN_URL
                + "?appid=" + wxProperties.getMp().getAppId()
                + "&secret=" + wxProperties.getMp().getAppSecret()
                + "&code=" + code
                + "&grant_type=authorization_code";

        MpOauthAccessTokenResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpOauthAccessTokenResponse.class)
                .block();

        return assertWxSuccess(response, "服务号网页授权 code 换 openid 失败");
    }

    /**
     * 获取服务号 JS-SDK jsapi_ticket。
     * <p>
     * ticket 需要配合当前页面 URL 生成 wx.config 签名。
     * </p>
     *
     * @param forceRefresh 是否强制刷新
     * @return jsapi_ticket
     */
    public String getMpJsApiTicket(boolean forceRefresh) {
        if (!forceRefresh) {
            String cachedTicket = redisService.getString(RedisKeyConstant.WX_MP_JSAPI_TICKET);
            if (StringUtils.hasText(cachedTicket)) {
                return cachedTicket;
            }
        }

        String token = getMpAccessToken(false);
        MpJsApiTicketResponse response = callGetJsApiTicket(token);

        if (response != null && response.isTokenExpired()) {
            log.warn("微信服务号 access_token 失效，刷新后重试获取 jsapi_ticket");
            token = getMpAccessToken(true);
            response = callGetJsApiTicket(token);
        }

        MpJsApiTicketResponse valid = assertWxSuccess(response, "获取服务号 jsapi_ticket 失败");
//        int ttlSeconds = Math.max(60, valid.getExpiresIn() - 200);
        redisService.set(RedisKeyConstant.WX_MP_JSAPI_TICKET,
                valid.getTicket(), RedisKeyConstant.WX_MP_JSAPI_TICKET_TTL);
        return valid.getTicket();
    }

    /**
     * 发送服务号订阅通知。
     * <p>
     * 仅封装微信接口调用与 token 失效重试，不判断业务是否应该发送。
     * </p>
     *
     * @param request 服务号订阅通知请求体
     * @return 微信发送结果
     */
    public MpSubscribeMessageResponse sendMpSubscribeMessage(MpSubscribeMessageRequest request) {
        String token = getMpAccessToken(false);
        MpSubscribeMessageResponse response = callSendMpSubscribeMessage(request, token);

        if (response != null && response.isTokenExpired()) {
            log.warn("微信服务号 access_token 失效，刷新后重试发送订阅通知，toUser={}, templateId={}",
                    request.getToUser(), request.getTemplateId());

            token = getMpAccessToken(true);
            response = callSendMpSubscribeMessage(request, token);
        }

        return assertWxSuccess(response, "发送服务号订阅通知失败");
    }

// ========================================================================================

    /**
     * 调用微信服务号订阅通知发送接口。
     */
    private MpSubscribeMessageResponse callSendMpSubscribeMessage(
            MpSubscribeMessageRequest request,
            String token
    ) {
        return webClient.post()
                .uri(MP_SUBSCRIBE_BIZ_SEND_URL + "?access_token=" + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MpSubscribeMessageResponse.class)
                .block();
    }

    /**
     * 调用微信获取 jsapi_ticket 接口。
     */
    private MpJsApiTicketResponse callGetJsApiTicket(String token) {
        String url = MP_JSAPI_TICKET_URL
                + "?access_token=" + token
                + "&type=jsapi";

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpJsApiTicketResponse.class)
                .block();
    }

    /**
     * 调用微信创建二维码接口。
     *
     * @param scene         二维码场景值
     * @param expireSeconds 过期秒数
     * @param token         服务号 access_token
     * @return 微信接口响应
     */
    private MpQrCodeCreateResponse callCreateQr(String scene, int expireSeconds, String token) {
        return webClient.post()
                .uri(MP_CREATE_QR_URL + "?access_token=" + token)
                .bodyValue(MpQrCodeCreateResponse.buildRequest(scene, expireSeconds))
                .retrieve()
                .bodyToMono(MpQrCodeCreateResponse.class)
                .block();
    }

    /**
     * 调用微信服务号模板消息发送接口。
     *
     * @param request 模板消息请求体
     * @param token   服务号 access_token
     * @return 微信接口响应
     */
    private MpTemplateMessageResponse callSendTemplate(MpTemplateMessageRequest request, String token) {
        return webClient.post()
                .uri(MP_TEMPLATE_SEND_URL + "?access_token=" + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MpTemplateMessageResponse.class)
                .block();
    }

    /**
     * 调用微信文本审核接口。
     */
    private WxMsgSecCheckResponse callMsgSecCheck(WxMsgSecCheckRequest request, String token) {
        return webClient.post()
                .uri(MINI_MSG_SEC_CHECK_URL + "?access_token=" + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(WxMsgSecCheckResponse.class)
                .block();
    }

    /**
     * 调用微信多媒体异步审核接口。
     */
    private WxMediaCheckAsyncResponse callMediaCheckAsync(WxMediaCheckAsyncRequest request, String token) {
        return webClient.post()
                .uri(MINI_MEDIA_CHECK_ASYNC_URL + "?access_token=" + token)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(WxMediaCheckAsyncResponse.class)
                .block();
    }

    /**
     * 校验微信接口通用返回码。
     *
     * @param response    微信接口返回对象
     * @param errorPrefix 自定义错误前缀，用于拼接业务日志与异常文案
     * @param <T>         继承自 WxApiResult 的具体响应类型
     * @return 校验通过后的原始 response
     * @throws BusinessException 当响应为空或 errcode 非 0 时抛出
     */
    private <T extends WxApiResult> T assertWxSuccess(T response, String errorPrefix) {
        if (response == null) {
            throw new BusinessException(ResultCode.WX_API_ERROR, errorPrefix + "，微信返回为空");
        }
        if (!response.isSuccess()) {
            String detail = errorPrefix + "，errcode=" + response.getErrcode() + ", errmsg=" + response.getErrmsg();
            log.error(detail);
            throw new BusinessException(ResultCode.WX_API_ERROR, detail);
        }
        return response;
    }

}
