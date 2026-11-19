package cn.jualn.miniapp.module.notify.channel;

/** Bounded channel-neutral outcome; contains no raw provider response, exception or credentials. */
public record NotificationSendResult(Outcome outcome, String providerCode, String messageId) {
    public enum Outcome { ACCEPTED, REJECTED_RETRYABLE, REJECTED_PERMANENT, UNKNOWN }
    public static NotificationSendResult unknown() {return new NotificationSendResult(Outcome.UNKNOWN,null,null);}
}
