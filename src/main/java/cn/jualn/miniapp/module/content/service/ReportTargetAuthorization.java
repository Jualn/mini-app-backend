package cn.jualn.miniapp.module.content.service;

/** Report owner proves an existing case and the current exact target scope. No general content grant. */
public interface ReportTargetAuthorization {
    boolean mayRead(String type, long id, long actor);
    boolean mayDispose(String type, long id, long actor);
    void requireDisposal(String type, long id, long actor);
}
