package cn.jualn.miniapp.infrastructure.queue.dispatch;

import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消息分发器，负责将消息分发给对应的 Handler 处理
 * <p>执行步骤：</p>
 * <ol>
 *     <il>根据 topic 选择对应的 handler来处理</il>
 *     <il>根据 topic 选择对应 message 进行反序列化对应泛型 QueueMessage</il>
 *     <il>将 message 提交到 handler处理</il>
 * </ol>
 */
@Slf4j
@Component
public class MessageDispatcher {

    private final ObjectMapper objectMapper;
    private final QueueRegistry queueRegistry;

    public MessageDispatcher(ObjectMapper objectMapper, QueueRegistry queueRegistry) {
        this.objectMapper = objectMapper;
        this.queueRegistry = queueRegistry;
    }


    /**
     * 单消费者执行多 Topic
     * <p>执行步骤：</p>
     * <ol>
     *     <il>根据 topic 选择对应的 handler来处理</il>
     *     <il>根据 topic 选择对应 message 进行反序列化对应泛型 QueueMessage</il>
     *     <il>将 message 提交到 handler处理</il>
     * </ol>
     * @param topic 消费类型，audit.image
     * @param msg 序列化 JSON 后的 message，包含泛型
     * @throws RuntimeException 反序列化泛型类失败
     */
    public void dispatch(String topic, String msg) {
        log.debug("[consumer][入参] topic={}", topic);

        Class<?> payloadType = queueRegistry.getPayloadClass(topic);
        if (payloadType == null) {
            throw new RuntimeException("Unknown topic: " + topic);
        }

        // 根据 topic 获取对应 handler
        // 这里接收类型也只能写 <?> 未知，如果写了具体的，不管是否子类，它只会接收指定的类<xx>在使用时就会出错
        QueueHandler<?> handler = queueRegistry.getHandler(payloadType);
        if (handler == null) {
            log.warn("[consumer][警告] skip consumer, reason=can not find handler, topic = {}",
                    topic);
            return;
        }

        // 创建包含泛型的 JavaType 用于反序列化 Java 的 Type 会擦去泛型参数
        JavaType javaType = objectMapper.getTypeFactory()
                .constructParametricType(QueueMessage.class, payloadType);

        QueueMessage<?> message;
        try {
            // 接收 <?> 未知类型的变量，对于未知在反序列化时 Jackson 会根据JavaType 转化为对应泛型
            // 如果使用继承的子类QueueMessageBody，就会导致 Jackson 直接转化为其类型，而不是根据JavaType
            message = objectMapper.readValue(
                    msg,
                    javaType
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化获取 QueueMessage 失败",e);
        }

        // 在接收的变量类型是 <?> 未知的，而我们在定义的接口中 T 是继承了 QueueMessageBody
        // 所以需要传入对应类或者子类的变量类型，需要强制转化，因为编译器不知道是什么类型，就会报错
        @SuppressWarnings("unchecked")  // 告诉编译器，已确认类型，不要检查了
        QueueHandler<MessagePayload> h = (QueueHandler<MessagePayload>) handler;
        @SuppressWarnings("unchecked")
        QueueMessage<MessagePayload> m = (QueueMessage<MessagePayload>) message;

        h.handle(m);
    }
}
