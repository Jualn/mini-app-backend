package cn.jualn.miniapp.module.media.service;

/** Business owner admission inside each short private-upload/register transaction, including finalization after remote IO. */
public interface PointMaterialAdmission {
    void begin(long user);
    void finish(long user);
}
