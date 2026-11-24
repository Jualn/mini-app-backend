package cn.jualn.miniapp.module.publicevent.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicEventSubscriptionVO(boolean subscribed, OffsetDateTime subscribedAt) {
}
