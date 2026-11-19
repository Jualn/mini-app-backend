package cn.jualn.miniapp.module.person.service.access;

import java.time.LocalDateTime;

/**
 * Session and capability boundary. It never supplies identity or membership facts.
 */
public interface PersonAuthentication {
    long actor();

    void require(long actor, String namespace, String permission, LocalDateTime at);
}
