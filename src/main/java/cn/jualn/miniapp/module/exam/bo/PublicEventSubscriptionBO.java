package cn.jualn.miniapp.module.exam.bo;

import java.time.LocalDateTime;

public record PublicEventSubscriptionBO(boolean subscribed, LocalDateTime subscribedAt) {
}
