package cn.jualn.miniapp.third.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务号订阅通知字段渲染器。
 *
 * 根据模板字段映射配置，将业务 payload 转换成微信模板 data。
 */
@Component
public class WxMpNoticeFieldRenderer {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{(\\w+)}");

    private static final String FORMATTER_TEXT = "text";
    private static final String FORMATTER_DATETIME = "datetime";
    private static final String FORMATTER_DATE = "date";

    /**
     * 渲染微信模板 data 字段。
     *
     * @param template 模板配置
     * @param payload 业务数据，key 为业务字段名
     * @return 微信订阅通知 data
     */
    public Map<String, MpSubscribeMessageRequest.DataItem> render(
            WxMpNoticeTemplateProperties.Template template,
            Map<String, Object> payload
    ) {
        Map<String, MpSubscribeMessageRequest.DataItem> result = new LinkedHashMap<>();

        template.getFields().forEach((wxField, mapping) -> {
            Object rawValue = payload.get(mapping.getSource());
            String value = formatValue(rawValue, mapping);

            if (!StringUtils.hasText(value)) {
                if (Boolean.TRUE.equals(mapping.getRequired())) {
                    throw new BusinessException(
                            ResultCode.PARAM_ERROR,
                            "服务号通知字段缺失：" + template.getType() + "." + wxField + " -> " + mapping.getSource()
                    );
                }

                value = mapping.getDefaultValue();
            }

            if (!StringUtils.hasText(value)) {
                value = "";
            }

            value = limitLength(value, mapping.getMaxLength());

            result.put(wxField, MpSubscribeMessageRequest.DataItem.builder()
                    .value(value)
                    .build());
        });

        return result;
    }

    /**
     * 将 pagePath 中的 {key} 替换为 payload 里对应的值。
     * 例：/pages/detail/?id={targetId} → /pages/detail/?id=123
     */
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
                        ResultCode.PARAM_ERROR,
                        "服务号通知 pagePath 参数缺失：" + key
                );
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(raw.toString()));
        }

        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 根据配置格式化字段值。
     */
    private String formatValue(Object rawValue, WxMpNoticeTemplateProperties.Field mapping) {
        if (rawValue == null) {
            return null;
        }

        String formatter = mapping.getFormatter();

        if (FORMATTER_DATETIME.equalsIgnoreCase(formatter)) {
            if (rawValue instanceof LocalDateTime time) {
                return DATE_TIME_FORMATTER.format(time);
            }
            return String.valueOf(rawValue);
        }

        if (FORMATTER_DATE.equalsIgnoreCase(formatter)) {
            if (rawValue instanceof LocalDateTime time) {
                return DATE_FORMATTER.format(time);
            }
            return String.valueOf(rawValue);
        }

        return String.valueOf(rawValue);
    }

    /**
     * 按配置裁剪长度，避免微信字段超长。
     */
    private String limitLength(String value, Integer maxLength) {
        if (!StringUtils.hasText(value) || maxLength == null || maxLength <= 0) {
            return value;
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }
}