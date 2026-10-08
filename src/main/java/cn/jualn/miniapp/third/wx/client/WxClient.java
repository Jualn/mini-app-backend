package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import java.time.Duration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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
    private final MeterRegistry meterRegistry;
    private final Object mpTokenMonitor = new Object();
    private final Object miniTokenMonitor = new Object();
    private final Object jsTicketMonitor = new Object();

    /**
     * 小程序登录：用 code 换取 openid / session_key。
     *
     * @param code 小程序前端调用 wx.login 获取的临时登录凭证
     * @return 小程序会话信息（含 openid、sessionKey、unionid 等）
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public MiniSessionResponse getMiniSession(String code) {
        URI url = wxUri(MINI_CODE2SESSION_URL,
                "appid", wxProperties.getMa().getAppId(),
                "secret", wxProperties.getMa().getAppSecret(),
                "js_code", code, "grant_type", "authorization_code");
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
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public String getMpAccessToken(boolean forceRefresh) {
        String cacheKey = RedisKeyConstant.wxAccessToken("mp", wxProperties.getMp().getAppId());
        if (!forceRefresh) {
            String cachedToken = redisService.getString(cacheKey);
            if (StringUtils.hasText(cachedToken)) {
                return cachedToken;
            }
        }
        synchronized (mpTokenMonitor) {
            if (!forceRefresh) {
                String cachedToken = redisService.getString(cacheKey);
                if (StringUtils.hasText(cachedToken)) return cachedToken;
            }
            return fetchMpAccessToken(cacheKey);
        }
    }

    private String fetchMpAccessToken(String cacheKey) {
        URI url = wxUri(ACCESS_TOKEN_URL, "grant_type", "client_credential",
                "appid", wxProperties.getMp().getAppId(), "secret", wxProperties.getMp().getAppSecret());
        MpAccessTokenResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpAccessTokenResponse.class)
                .block();
        MpAccessTokenResponse valid = assertWxSuccess(response, "服务号 access_token 获取失败");
        validateCredential(valid.getAccessToken(), valid.getExpiresIn(), "服务号 access_token");
        redisService.set(cacheKey, valid.getAccessToken(), credentialTtl(valid.getExpiresIn()));
        return valid.getAccessToken();
    }

    /**
     * 获取小程序 access_token。
     * token 会缓存到 Redis，避免高频请求触发微信限流。
     *
     * @param forceRefresh 是否强制跳过缓存并重新向微信请求 token
     * @return 可用于小程序接口调用的 access_token
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public String getMiniAccessToken(boolean forceRefresh) {
        String cacheKey = RedisKeyConstant.wxAccessToken("ma", wxProperties.getMa().getAppId());
        if (!forceRefresh) {
            String cachedToken = redisService.getString(cacheKey);
            if (StringUtils.hasText(cachedToken)) {
                return cachedToken;
            }
        }
        synchronized (miniTokenMonitor) {
            if (!forceRefresh) {
                String cachedToken = redisService.getString(cacheKey);
                if (StringUtils.hasText(cachedToken)) return cachedToken;
            }
            return fetchMiniAccessToken(cacheKey);
        }
    }

    private String fetchMiniAccessToken(String cacheKey) {
        URI url = wxUri(ACCESS_TOKEN_URL, "grant_type", "client_credential",
                "appid", wxProperties.getMa().getAppId(), "secret", wxProperties.getMa().getAppSecret());
        MiniAccessTokenResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MiniAccessTokenResponse.class)
                .block();
        MiniAccessTokenResponse valid = assertWxSuccess(response, "小程序 access_token 获取失败");
        validateCredential(valid.getAccessToken(), valid.getExpiresIn(), "小程序 access_token");
        redisService.set(cacheKey, valid.getAccessToken(), credentialTtl(valid.getExpiresIn()));
        return valid.getAccessToken();
    }

    /**
     * 小程序文本内容安全检测。
     *
     * @param request 文本审核请求体
     * @return 微信审核结果（含 traceId/result/detail）
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public WxMsgSecCheckResponse msgSecCheck(WxMsgSecCheckRequest request) {
        String token = getMiniAccessToken(false);
        WxMsgSecCheckResponse response = callMsgSecCheck(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信小程序 access_token 失效，刷新后重试文本审核");
            token = refreshMiniAccessToken(token);
            response = callMsgSecCheck(request, token);
        }
        return assertWxSuccess(response, "小程序文本内容审核失败");
    }

    /**
     * 小程序多媒体内容安全异步检测。
     *
     * @param request 多媒体审核请求体
     * @return 微信异步审核提交结果（含 traceId）
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public WxMediaCheckAsyncResponse mediaCheckAsync(WxMediaCheckAsyncRequest request) {
        String token = getMiniAccessToken(false);
        WxMediaCheckAsyncResponse response = callMediaCheckAsync(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信小程序 access_token 失效，刷新后重试多媒体审核提交");
            token = refreshMiniAccessToken(token);
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
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public MpQrCodeCreateResponse createQrSceneTicket(String scene, int expireSeconds) {
        String token = getMpAccessToken(false);
        MpQrCodeCreateResponse response = callCreateQr(scene, expireSeconds, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试创建二维码");
            token = refreshMpAccessToken(token);
            response = callCreateQr(scene, expireSeconds, token);
        }
        return assertWxSuccess(response, "创建服务号二维码失败");
    }

    /**
     * 查询服务号用户信息（关注状态、昵称等）。
     *
     * @param mpOpenid 服务号侧用户 openid
     * @return 服务号用户信息
     * @throws ExternalServiceException 当微信接口返回失败或响应为空时抛出
     */
    public MpUserInfoResponse getMpUserInfo(String mpOpenid) {
        String token = getMpAccessToken(false);
        URI url = wxUri(MP_USER_INFO_URL, "access_token", token, "openid", mpOpenid, "lang", "zh_CN");
        MpUserInfoResponse response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(MpUserInfoResponse.class)
                .block();
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试获取用户信息");
            String refreshToken = refreshMpAccessToken(token);
            URI refreshUrl = wxUri(MP_USER_INFO_URL, "access_token", refreshToken, "openid", mpOpenid, "lang", "zh_CN");
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
     */
    public MpTemplateMessageResponse sendMpTemplateMessage(MpTemplateMessageRequest request) {
        String token = getMpAccessToken(false);
        MpTemplateMessageResponse response = callSendTemplate(request, token);
        if (response != null && response.isTokenExpired()) {
            log.warn("微信 access_token 失效，刷新后重试发送模板消息，templateId={}",
                    request.getTemplateId());
            String refreshToken = refreshMpAccessToken(token);
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
        URI url = wxUri(MP_OAUTH_ACCESS_TOKEN_URL,
                "appid", wxProperties.getMp().getAppId(), "secret", wxProperties.getMp().getAppSecret(),
                "code", code, "grant_type", "authorization_code");

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
        String cacheKey = RedisKeyConstant.wxJsApiTicket(wxProperties.getMp().getAppId());
        if (!forceRefresh) {
            String cachedTicket = redisService.getString(cacheKey);
            if (StringUtils.hasText(cachedTicket)) {
                return cachedTicket;
            }
        }
        synchronized (jsTicketMonitor) {
            if (!forceRefresh) {
                String cachedTicket = redisService.getString(cacheKey);
                if (StringUtils.hasText(cachedTicket)) return cachedTicket;
            }
            String token = getMpAccessToken(false);
            MpJsApiTicketResponse response = callGetJsApiTicket(token);
            if (response != null && response.isTokenExpired()) {
                log.warn("微信服务号 access_token 失效，刷新后重试获取 jsapi_ticket");
                token = refreshMpAccessToken(token);
                response = callGetJsApiTicket(token);
            }
            MpJsApiTicketResponse valid = assertWxSuccess(response, "获取服务号 jsapi_ticket 失败");
            validateCredential(valid.getTicket(), valid.getExpiresIn(), "服务号 jsapi_ticket");
            redisService.set(cacheKey, valid.getTicket(), credentialTtl(valid.getExpiresIn()));
            return valid.getTicket();
        }
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
            log.warn("微信服务号 access_token 失效，刷新后重试发送订阅通知，templateId={}",
                    request.getTemplateId());

            token = refreshMpAccessToken(token);
            response = callSendMpSubscribeMessage(request, token);
        }

        return assertWxSuccess(response, "发送服务号订阅通知失败");
    }

// ========================================================================================

    /** Generates a temporary mini-program code, never a service-account ticket QR. */
    public byte[] generateMiniProgramCode(String page, String sceneCode, String envVersion, boolean checkPath) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "failure", category = "remote";
        try {
            byte[] png = Mono.fromCallable(() -> safeMiniCodeToken(null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(token -> requestMiniCode(new MiniProgramCodeRequest(page, sceneCode, envVersion, checkPath, 430, false), token)
                            .flatMap(response -> {
                                Integer code = miniCodeError(response);
                                if (Integer.valueOf(40001).equals(code) || Integer.valueOf(42001).equals(code)) {
                                    return Mono.fromCallable(() -> safeMiniCodeToken(token))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .flatMap(refreshed -> requestMiniCode(
                                                    new MiniProgramCodeRequest(page, sceneCode, envVersion, checkPath, 430, false), refreshed));
                                }
                                return Mono.just(response);
                            }))
                    .map(response -> {
                        Integer code = miniCodeError(response);
                        if (code != null) throw new MiniCodeFailure(Integer.toString(code), "remote");
                        return normalizeMiniCode(response.body());
                    })
                    .timeout(Duration.ofSeconds(25)).block();
            if (png == null) throw new IllegalStateException("Empty WeChat code response");
            result = "success";
            category = "internal";
            return png;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            String providerCode = "CODE_UNAVAILABLE";
            while (cause != null) {
                if (cause instanceof java.util.concurrent.TimeoutException) category = "timeout";
                if (cause instanceof MiniCodeFailure controlled) { category = controlled.category; providerCode = controlled.code; }
                cause = cause.getCause();
            }
            log.warn("result=failure errorCategory={} provider=wechat operation=miniCode code={}", category, providerCode);
            // Discard provider URI/body/cause: it may contain access_token, scene or secrets.
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat", providerCode,
                    "Mini program code is temporarily unavailable");
        } finally {
            sample.stop(meterRegistry.timer("jualn.admin.qr.login.wechat.code.duration",
                    "result", result, "error.category", category));
        }
    }

    private record MiniCodeResponse(MediaType contentType, byte[] body) {
        @Override public String toString() { return "MiniCodeResponse[body redacted]"; }
    }

    private String safeMiniCodeToken(String stale) {
        try { return stale == null ? getMiniAccessToken(false) : refreshMiniAccessToken(stale); }
        catch (RuntimeException failure) { throw new MiniCodeFailure("TOKEN_UNAVAILABLE", miniCodeFailureCategory(failure)); }
    }

    private static final class MiniCodeFailure extends RuntimeException {
        private final String code;
        private final String category;
        private MiniCodeFailure(String code, String category) {
            super("WeChat mini code unavailable"); this.code = code; this.category = category;
        }
    }
    private String miniCodeFailureCategory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException) return "timeout";
        }
        return "remote";
    }

    private Mono<MiniCodeResponse> requestMiniCode(MiniProgramCodeRequest request, String token) {
        return webClient.post().uri(wxUri("https://api.weixin.qq.com/wxa/getwxacodeunlimit", "access_token", token))
                .accept(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.APPLICATION_JSON)
                .bodyValue(request).exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(new MiniCodeFailure("HTTP_" + response.statusCode().value(), "remote")));
                    }
                    MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                    return response.bodyToMono(byte[].class).map(body -> new MiniCodeResponse(contentType, body));
                }).onErrorMap(failure -> failure instanceof MiniCodeFailure ? failure
                        : new MiniCodeFailure("TRANSPORT_UNAVAILABLE", miniCodeFailureCategory(failure)));
    }

    private Integer miniCodeError(MiniCodeResponse response) {
        byte[] body = response.body();
        if (body.length == 0 || body.length > 2 * 1024 * 1024) throw new IllegalStateException("Invalid WeChat code body size");
        if (MediaType.APPLICATION_JSON.isCompatibleWith(response.contentType()) || body[0] == '{') {
            try {
                var json = new ObjectMapper().readTree(body);
                if (!json.has("errcode") || !json.get("errcode").canConvertToInt()) throw new IllegalStateException("Invalid WeChat JSON response");
                return json.get("errcode").intValue();
            } catch (java.io.IOException e) { throw new IllegalStateException("Invalid WeChat JSON response"); }
        }
        if (!MediaType.IMAGE_PNG.isCompatibleWith(response.contentType())
                && !MediaType.IMAGE_JPEG.isCompatibleWith(response.contentType())
                && !MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(response.contentType())) {
            throw new IllegalStateException("Unsupported WeChat code media type");
        }
        return null;
    }

    public static byte[] normalizeMiniCode(byte[] body) {
        boolean png = body.length >= 8 && body[0] == (byte) 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
        boolean jpeg = body.length >= 3 && body[0] == (byte) 0xff && body[1] == (byte) 0xd8 && body[2] == (byte) 0xff;
        if ((!png && !jpeg) || body.length > 2097152) throw new IllegalStateException("Invalid code image signature");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(body))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalStateException("Unreadable code image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || (long) width * height > 4194304) {
                    throw new IllegalStateException("Code image dimensions exceeded");
                }
                var image = reader.read(0);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", out) || out.size() > 2097152) throw new IllegalStateException("Invalid normalized code size");
                return out.toByteArray();
            } finally { reader.dispose(); }
        } catch (java.io.IOException e) { throw new IllegalStateException("Damaged code image"); }
    }

    /**
     * 调用微信服务号订阅通知发送接口。
     */
    private MpSubscribeMessageResponse callSendMpSubscribeMessage(
            MpSubscribeMessageRequest request,
            String token
    ) {
        return webClient.post()
                .uri(wxUri(MP_SUBSCRIBE_BIZ_SEND_URL, "access_token", token))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MpSubscribeMessageResponse.class)
                .block();
    }

    /**
     * 调用微信获取 jsapi_ticket 接口。
     */
    private MpJsApiTicketResponse callGetJsApiTicket(String token) {
        URI url = wxUri(MP_JSAPI_TICKET_URL, "access_token", token, "type", "jsapi");

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
                .uri(wxUri(MP_CREATE_QR_URL, "access_token", token))
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
                .uri(wxUri(MP_TEMPLATE_SEND_URL, "access_token", token))
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
                .uri(wxUri(MINI_MSG_SEC_CHECK_URL, "access_token", token))
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
                .uri(wxUri(MINI_MEDIA_CHECK_ASYNC_URL, "access_token", token))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(WxMediaCheckAsyncResponse.class)
                .block();
    }

    // Treat dynamic values as opaque URI variables. Encoding only a concatenated query
    // would leave '&'/'=' able to inject parameters and braces able to act as templates.
    private URI wxUri(String endpoint, String... parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            String variable = "value" + i;
            builder.queryParam(parameters[i], "{" + variable + "}");
            values.put(variable, parameters[i + 1]);
        }
        return builder.encode().buildAndExpand(values).toUri();
    }

    /**
     * 校验微信接口通用返回码。
     *
     * @param response    微信接口返回对象
     * @param errorPrefix 自定义错误前缀，用于拼接业务日志与异常文案
     * @param <T>         继承自 WxApiResult 的具体响应类型
     * @return 校验通过后的原始 response
     * @throws ExternalServiceException 当响应为空或 errcode 非 0 时抛出
     */
    private <T extends WxApiResult> T assertWxSuccess(T response, String errorPrefix) {
        if (response == null) {
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                    errorPrefix + "，微信返回为空");
        }
        if (!response.isSuccess()) {
            String detail = errorPrefix + "，errcode=" + response.getErrcode() + ", errmsg=" + response.getErrmsg();
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                    String.valueOf(response.getErrcode()), detail);
        }
        return response;
    }

    private String refreshMpAccessToken(String staleToken) {
        String cacheKey = RedisKeyConstant.wxAccessToken("mp", wxProperties.getMp().getAppId());
        synchronized (mpTokenMonitor) {
            String current = redisService.getString(cacheKey);
            if (StringUtils.hasText(current) && !current.equals(staleToken)) return current;
            return fetchMpAccessToken(cacheKey);
        }
    }

    private String refreshMiniAccessToken(String staleToken) {
        String cacheKey = RedisKeyConstant.wxAccessToken("ma", wxProperties.getMa().getAppId());
        synchronized (miniTokenMonitor) {
            String current = redisService.getString(cacheKey);
            if (StringUtils.hasText(current) && !current.equals(staleToken)) return current;
            return fetchMiniAccessToken(cacheKey);
        }
    }

    private void validateCredential(String value, Integer expiresIn, String credentialName) {
        if (!StringUtils.hasText(value) || expiresIn == null || expiresIn <= 200) {
            throw new ExternalServiceException(ResultCode.WX_API_ERROR, "wechat",
                    credentialName + " 返回内容无效");
        }
    }

    private Duration credentialTtl(Integer expiresIn) {
        return Duration.ofSeconds(expiresIn - 200L);
    }

}
