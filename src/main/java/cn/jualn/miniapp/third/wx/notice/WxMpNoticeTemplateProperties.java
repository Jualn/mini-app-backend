package cn.jualn.miniapp.third.wx.notice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务号订阅通知模板配置。
 * <p>
 * 统一维护：
 * 1. 模板 ID
 * 2. H5 订阅页展示名称
 * 3. 是否启用
 * 4. 微信模板字段与业务字段的映射关系
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx.mp")
public class WxMpNoticeTemplateProperties {

    /**
     * 服务号订阅通知模板配置。
     * <p>
     * key 示例：comment-reply、activity-start。
     */
    private Map<String, Template> noticeTemplates = new LinkedHashMap<>();

    @Data
    public static class Template {

        /**
         * 业务通知类型，如 comment_reply、activity_start。
         */
        private String type;

        /**
         * 展示名称，如 评论回复通知。
         */
        private String name;

        /**
         * 微信服务号订阅通知模板 ID。
         */
        private String templateId;

        /**
         * 后端是否允许发送。
         */
        private Boolean enabled = true;

        /**
         * 是否展示在 H5 订阅入口。
         */
        private Boolean subscribeVisible = true;

        /**
         * 点击通知后跳转的小程序页面路径。
         */
        private String pagePath;

        /**
         * 微信模板字段映射。
         * <p>
         * key 示例：thing1、time2、phrase3。
         */
        private Map<String, Field> fields = new LinkedHashMap<>();
    }

    @Data
    public static class Field {

        /**
         * 字段说明，仅用于维护人员识别。
         */
        private String name;

        /**
         * 业务数据来源字段。
         * <p>
         * 例如：
         * postTitle、replyContent、activityTitle、startTime。
         */
        private String source;

        /**
         * 是否必填。
         */
        private Boolean required = false;

        /**
         * 默认值。
         * <p>
         * 当业务字段为空且非必填时使用。
         */
        private String defaultValue;

        /**
         * 最大长度。
         * <p>
         * 按微信模板字段限制配置，避免发送时因内容过长失败。
         */
        private Integer maxLength;

        /**
         * 格式化方式。
         * <p>
         * 支持：
         * text      普通文本
         * datetime  时间
         * date      日期
         */
        private String formatter = "text";
    }
}