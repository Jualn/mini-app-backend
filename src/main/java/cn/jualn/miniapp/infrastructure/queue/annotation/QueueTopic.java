package cn.jualn.miniapp.infrastructure.queue.annotation;

import java.lang.annotation.*;

/**
 * 队列主题注解，用于标识消息处理器所属的队列主题。
 * 如果不是特殊情况不需要对消息体写上，会自动生成以消息体类名(去除了Payload)为主题的队列。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueueTopic {
    String value();
}
