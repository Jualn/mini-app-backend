package cn.jualn.miniapp.module.publicevent.bo;

import java.time.LocalDateTime;

public record PublicEventSubscriptionBO(boolean subscribed, LocalDateTime subscribedAt) {
}
