package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.springframework.util.StringUtils;
import java.util.Map;
import java.util.regex.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Application route mapping; provider clients and value formatters never know these paths. */
public final class WxNotificationTargetResolver {
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{(\\w+)}");
    public boolean supports(String type,String target) {
        if(!WxNoticeFieldCatalog.knownType(type)||target==null)return false;
        if("INBOX".equals(target)||"SEMANTIC_TARGET".equals(target))return true;
        if(type.startsWith("ACTIVITY_"))return "ACTIVITY_DETAIL".equals(target);
        if(type.startsWith("PUBLIC_EVENT_"))return "PUBLIC_EVENT_DETAIL".equals(target);
        return "POST_COMMENTED".equals(type)&&"POST_DETAIL".equals(target);
    }
    public String resolve(WxEffectiveTemplate template, Map<String, Object> frozen) {
        String path = template.jumpTarget() == null ? resolvePath(template.pagePath(), frozen) : switch (template.jumpTarget()) {
            case "ACTIVITY_DETAIL" -> "/subpkg_activity/pages/detail/detail?activityId=" + positiveId(frozen.get("targetId"));
            case "PUBLIC_EVENT_DETAIL" -> "/subpkg_public_event/pages/detail/detail?publicEventId=" + positiveId(frozen.get("targetId"));
            case "POST_DETAIL" -> "/subpkg_community/pages/detail/detail?postId=" + positiveId(frozen.get("targetId"));
            case "SEMANTIC_TARGET" -> semanticPath(frozen);
            case "INBOX" -> "/pages/message/message";
            default -> throw payloadInvalid();
        };
        return originatingPath(path, frozen);
    }
    private String semanticPath(Map<String,Object> frozen) {
        if(frozen.get("target") instanceof Map<?,?> target && target.get("type")!=null) {
            return switch(target.get("type").toString()) {
                case "POST_DETAIL" -> "/subpkg_community/pages/detail/detail?postId="+positiveId(target.get("postId"));
                case "ACTIVITY_DETAIL" -> "/subpkg_activity/pages/detail/detail?activityId="+positiveId(target.get("activityId"));
                case "PUBLIC_EVENT_DETAIL" -> "/subpkg_public_event/pages/detail/detail?publicEventId="+positiveId(target.get("publicEventId"));
                default -> "/pages/message/message";
            };
        }
        return "/pages/message/message";
    }
    public String originatingPath(String path, Map<String, Object> frozen) {
        if (!StringUtils.hasText(path) || path.contains("..") || path.contains("%") || path.startsWith("//")
                || path.contains(":") || path.contains("\\") || path.contains("#") || path.contains("{")
                || path.chars().anyMatch(Character::isWhitespace)) throw payloadInvalid();
        String base = path.split("\\?", 2)[0];
        if (!SetHolder.PAGES.contains(base.startsWith("/") ? base.substring(1) : base)) throw payloadInvalid();
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(path)
                .replaceQueryParam("notificationId", positiveId(frozen.get("notificationId")))
                .build().encode().toUriString();
    }

    private static class SetHolder {
        private static final java.util.Set<String> PAGES = java.util.Set.of("pages/message/message", "pages/index/index",
                "subpkg_activity/pages/detail/detail", "subpkg_public_event/pages/detail/detail", "subpkg_community/pages/detail/detail");
    }

    private String positiveId(Object value) {
        if (value == null || !value.toString().matches("[1-9][0-9]{0,19}")) throw payloadInvalid();
        return value.toString();
    }

    public String resolvePath(String pathTemplate, Map<String, Object> payload) {
        if (!StringUtils.hasText(pathTemplate)) {
            return pathTemplate;
        }

        StringBuilder sb = new StringBuilder();
        Matcher m = PATH_VARIABLE_PATTERN.matcher(pathTemplate);

        while (m.find()) {
            String key = m.group(1);
            Object raw = payload.get(key);

            if (raw == null || !StringUtils.hasText(raw.toString())) {
                throw new BusinessException(
                        ResultCode.WX_NOTICE_PAYLOAD_INVALID,
                        "服务号通知 pagePath 参数缺失：" + key
                );
            }

            String encoded = URLEncoder.encode(raw.toString(), StandardCharsets.UTF_8).replace("+", "%20");
            m.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }

        m.appendTail(sb);
        return sb.toString();
    }

    private BusinessException payloadInvalid() {
        return new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID, "TEMPLATE_INPUT_INVALID");
    }
}
