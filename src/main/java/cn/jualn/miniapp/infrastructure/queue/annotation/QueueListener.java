package cn.jualn.miniapp.infrastructure.queue.annotation;

import java.lang.annotation.*;

/**
 * 通过自动扫描 component，可以找到所有实现了 {@code QueueListener} 的方法。
 * 获取所有 listeners 与当前注解中的值对应绑定，以便后续可以通过 topic 找到对应的 listener
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QueueListener {
}
