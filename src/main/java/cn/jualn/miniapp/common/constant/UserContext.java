package cn.jualn.miniapp.common.constant;

/**
 * 用户上下文类，用于在当前线程中存储和获取用户相关的信息，例如用户ID。
 * 通过使用 ThreadLocal，可以确保每个线程都有自己的用户上下文，避免了在多线程环境中用户信息的混乱和泄露。
 * 这个类提供了设置、获取和清除用户ID的方法，方便在应用程序的不同部分访问用户信息。
 */
public class UserContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
