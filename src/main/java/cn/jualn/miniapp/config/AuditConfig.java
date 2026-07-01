package cn.jualn.miniapp.config;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核回调配置类。
 */
@Configuration
public class AuditConfig {

    /**
     * 注册审核结果回调实现类，扫描所有实现了 AuditResultCallback 接口的 Spring Bean，
     * 根据类上的 @AuditTarget 注解获取对应的 AuditScene，并将其与
     * @param callbacks Spring 容器中所有 AuditResultCallback 实现类的实例列表
     * @return 一个 Map，键为 AuditScene，值为对应的 AuditResultCallback 实现类实例
     */
    @Bean
    public Map<AuditScene, AuditResultCallback> callbackRegister(List<AuditResultCallback> callbacks) {
        Map<AuditScene, AuditResultCallback> map = new HashMap<>();
        for (AuditResultCallback callback : callbacks) {
            Class<?> callbackClass = AopUtils.getTargetClass(callback);
            AuditTarget annotation = callbackClass.getAnnotation(AuditTarget.class);
            if (annotation != null) {
                map.put(annotation.value(), callback);
            }
        }
        return map;
    }
}
