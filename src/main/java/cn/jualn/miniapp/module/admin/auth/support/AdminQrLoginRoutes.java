package cn.jualn.miniapp.module.admin.auth.support;

/** Exact v2 route classification shared by security and cache policy. */
public final class AdminQrLoginRoutes {
    private static final String ROOT = "/v1/admin/auth/";
    private AdminQrLoginRoutes() {}
    public static boolean mobile(String path, String method) {
        return method.equals("POST") && path.matches(ROOT + "qr-login-scans(?::confirm|:reject)?");
    }
    public static boolean web(String path, String method) {
        if (path.equals(ROOT + "qr-login-sessions")) return method.equals("POST");
        if (method.equals("GET")) return path.matches(ROOT + "qr-login-sessions/[^/:]+(?:/code)?");
        return method.equals("POST") && path.matches(ROOT + "qr-login-sessions/[^/:]+:(?:consume|cancel)");
    }
    public static boolean resource(String path) {
        return path.matches(ROOT + "qr-login-scans(?::confirm|:reject)?")
                || path.matches(ROOT + "qr-login-sessions(?:/[^/]+(?:/code)?)?");
    }
}
