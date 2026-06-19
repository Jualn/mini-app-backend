package cn.jualn.miniapp.infrastructure.queue.dispatch;

import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QueueRegistry {

    // topic -> payload class
    private final Map<String, Class<?>> topicToPayload = new HashMap<>();

    // payload class -> handler
    private final Map<Class<?>, QueueHandler<?>> payloadToHandler = new HashMap<>();

    public QueueRegistry(List<QueueHandler<?>> handlers) throws Exception {

        // ===== 扫描 payload（只启动一次）===== ,用于反序列化避免丢失泛型
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        scanner.addIncludeFilter(new AssignableTypeFilter(MessagePayload.class));

        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents("cn.jualn.miniapp.module");

        for (BeanDefinition bd : candidates) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            QueueTopic annotation = clazz.getAnnotation(QueueTopic.class);
            String topic;
            // 自动生成 or 读注解
            if (annotation == null) {
                topic = resolveTopic(clazz);
            } else {
                topic = annotation.value();
            }

            Class<?> oldPayloadClass = topicToPayload.put(topic, clazz);
            if (oldPayloadClass != null) {
                throw new RuntimeException(
                        "Duplicate queue topic: " + topic
                                + ", old=" + oldPayloadClass.getName()
                                + ", new=" + clazz.getName()
                );
            }
        }

        // =====  绑定 Handler（类型驱动）=====
        for (QueueHandler<?> handler : handlers) {
            Class<?> handlerClass = AopUtils.getTargetClass(handler);
            Class<?> messageClass = resolveGenericType(handlerClass);

            QueueHandler<?> oldHandler = payloadToHandler.put(messageClass, handler);
            if (oldHandler != null) {
                throw new RuntimeException(
                        "Duplicate queue handler for payload: " + messageClass.getName()
                                + ", old=" + oldHandler.getClass().getName()
                                + ", new=" + handler.getClass().getName()
                );
            }
        }
    }

    // ===== topic 生成（可替换为读注解）=====
    private String resolveTopic(Class<?> clazz) {
        String name = clazz.getSimpleName().replace("Payload", "");
        return name.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase();
    }

    // ===== 解析泛型 T =====
    private Class<?> resolveGenericType(Class<?> clazz) {
        for (Type type : clazz.getGenericInterfaces()) {
            if (type instanceof ParameterizedType pt &&
                    pt.getRawType().getTypeName().contains("QueueHandler")) {
                return (Class<?>) pt.getActualTypeArguments()[0];
            }
        }
        throw new RuntimeException("Cannot resolve generic type: " + clazz);
    }

    // ===== 对外 =====
    public Class<?> getPayloadClass(String topic) {
        return topicToPayload.get(topic);
    }

    public QueueHandler<?> getHandler(Class<?> messageClass) {
        return payloadToHandler.get(messageClass);
    }
}
