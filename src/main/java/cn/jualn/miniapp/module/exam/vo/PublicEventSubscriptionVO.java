package cn.jualn.miniapp.module.exam.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicEventSubscriptionVO(boolean subscribed, OffsetDateTime subscribedAt) {
}
