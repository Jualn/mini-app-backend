package cn.jualn.miniapp.module.post.service;

/**
 * A trusted current qualification decision. A positive decision requires traceable evidence.
 */
public interface MentorQualification {
    enum State {VALID, INELIGIBLE, UNAVAILABLE}

    record Decision(State state, String evidence, cn.jualn.miniapp.module.post.bo.MentorQaBO.Proof proof) {
        public Decision(State state,String evidence) {this(state,evidence,null);}
        public boolean valid() {
            return state == State.VALID && evidence != null && !evidence.isBlank() && evidence.length() <= 512;
        }
    }

    Decision evaluate(long userId);
    default Decision currentForOriginal(long user,String originalPerson) {return evaluate(user); }
    default void guardPublication(long user,Long reviewer) {guardWrite(user); }

    default void guardWrite(long userId) {
    }

    default Decision forWrite(long userId) {
        return evaluate(userId);
    }
}
