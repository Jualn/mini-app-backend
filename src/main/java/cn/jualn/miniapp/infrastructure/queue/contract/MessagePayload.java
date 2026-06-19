package cn.jualn.miniapp.infrastructure.queue.contract;

/**
 * 约束消息载荷的接口，所有消息载荷都必须实现此接口，以确保它们可以被队列系统正确处理。
 * 这个接口可以是一个标记接口，也可以包含一些通用的方法，例如获取消息类型、序列化和反序列化方法等。
 * 通过实现这个接口，消息载荷可以被队列系统识别和处理，从而实现消息的传递和处理逻辑。
 */
public interface MessagePayload {
}
