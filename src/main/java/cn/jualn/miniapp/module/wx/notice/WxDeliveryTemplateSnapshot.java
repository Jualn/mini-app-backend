package cn.jualn.miniapp.module.wx.notice;

import java.util.Map;

/** Durable provider payload without secrets or recipient identities. */
public record WxDeliveryTemplateSnapshot(int schemaVersion, String channel, String accountRef,
        String environment, String accountAppId, String product, String bindingRef,
        int bindingVersion, String definitionRef, int definitionVersion, String templateId,
        Map<String, String> data, String pagePath, String jumpAppId, String configDigest,
        String miniProgramState) {
    /** Compatibility constructor for existing Official Account snapshots. */
    public WxDeliveryTemplateSnapshot(int schemaVersion, String channel, String accountRef,
            String environment, String accountAppId, String product, String bindingRef,
            int bindingVersion, String definitionRef, int definitionVersion, String templateId,
            Map<String, String> data, String pagePath, String jumpAppId, String configDigest) {
        this(schemaVersion, channel, accountRef, environment, accountAppId, product, bindingRef,
                bindingVersion, definitionRef, definitionVersion, templateId, data, pagePath,
                jumpAppId, configDigest, null);
    }
    public WxDeliveryTemplateSnapshot {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
    public WxDeliveryTemplateSnapshot withAccounts(String account, String jump) {
        return new WxDeliveryTemplateSnapshot(schemaVersion, channel, accountRef, environment, account,
                product, bindingRef, bindingVersion, definitionRef, definitionVersion, templateId,
                data, pagePath, jump, configDigest, miniProgramState);
    }
}
