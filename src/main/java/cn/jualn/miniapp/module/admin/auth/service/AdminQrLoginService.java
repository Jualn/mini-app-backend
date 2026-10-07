package cn.jualn.miniapp.module.admin.auth.service;

import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;

public interface AdminQrLoginService {
    AdminQrCreatedBO createSession(String clientIp);
    AdminQrSessionBO querySession(String id, String secret, String clientIp);
    byte[] readCode(String id, String secret, String clientIp);
    AdminQrSessionBO cancelSession(String id, String secret, String clientIp);
    AdminQrSessionBO scanSession(String sceneCode, long subject, String clientIp);
    AdminQrSessionBO confirmSession(String sceneCode, long subject, String clientIp);
    AdminQrSessionBO rejectSession(String sceneCode, long subject, String clientIp);
    AdminQrLoginResultBO consumeSession(String id, String secret, String clientIp);
}
