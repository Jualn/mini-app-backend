package cn.jualn.miniapp.module.notify.async;

/** Durable v2 payload. All mutable delivery facts are re-read by deliveryId. */
public record NotificationDeliveryJobPayload(long deliveryId) {
}
