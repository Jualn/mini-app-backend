package cn.jualn.miniapp.module.post.service;

/**
 * Explicit fail-closed fallback retained for compatibility tests; never registered in production.
 */
public class UnavailableMentorQualification implements MentorQualification {
    @Override
    public Decision evaluate(long userId) {
        return new Decision(State.UNAVAILABLE, null);
    }
}
