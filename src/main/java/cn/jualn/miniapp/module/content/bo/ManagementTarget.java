package cn.jualn.miniapp.module.content.bo;

/** Real resource owner location. This projection grants no permission. */
public record ManagementTarget(String namespace, BusinessTarget businessTarget) {
    public record Reference(String type, long id) {}
    public record BusinessTarget(String type, String organizationId) {}
}
