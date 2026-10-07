package cn.jualn.miniapp.module.activity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivitySubscriptionVO(boolean subscribed, OffsetDateTime subscribedAt) {
}
