package cn.jualn.miniapp.module.person.service.access;

import java.time.LocalDateTime;

/**
 * Session and capability boundary. It never supplies identity or membership facts.
 */
public interface PersonAuthentication {
    long actor();

    default void verifyActor(long actor) {
        if (actor() != actor) throw cn.jualn.miniapp.module.person.PersonProblems.denied();
    }

    void require(long actor, String namespace, String permission, LocalDateTime at);
}
