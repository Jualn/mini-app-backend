package cn.jualn.miniapp.module.activity.bo;

import java.time.LocalDateTime;

public record ActivitySubscriptionBO(boolean subscribed, LocalDateTime subscribedAt) {
}
