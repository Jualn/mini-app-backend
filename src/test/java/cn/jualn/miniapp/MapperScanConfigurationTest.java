package cn.jualn.miniapp;

import cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper;
import cn.jualn.miniapp.infrastructure.async.job.JobHandler;
import cn.jualn.miniapp.infrastructure.async.message.DeadMessageMapper;
import cn.jualn.miniapp.infrastructure.async.message.EventConsumptionMapper;
import cn.jualn.miniapp.infrastructure.async.message.EventHandler;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxMapper;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperScanConfigurationTest {

    @Test
    void mybatisScansPreserveModuleMappersAndIncludeOnlyAnnotatedAsyncInterfaces() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        ClassPathMapperScanner moduleScanner = new ClassPathMapperScanner(registry, new StandardEnvironment());
        moduleScanner.registerFilters();
        moduleScanner.scan("cn.jualn.miniapp.module.**.mapper");

        ClassPathMapperScanner asyncScanner = new ClassPathMapperScanner(registry, new StandardEnvironment());
        asyncScanner.setAnnotationClass(Mapper.class);
        asyncScanner.registerFilters();
        asyncScanner.scan("cn.jualn.miniapp.infrastructure.async");

        assertMapperRegistered(registry, UserProfileMapper.class);
        assertMapperRegistered(registry, AsyncJobMapper.class);
        assertMapperRegistered(registry, OutboxMapper.class);
        assertMapperRegistered(registry, EventConsumptionMapper.class);
        assertMapperRegistered(registry, DeadMessageMapper.class);
        assertFalse(registry.containsBeanDefinition(beanName(JobHandler.class)));
        assertFalse(registry.containsBeanDefinition(beanName(EventHandler.class)));
    }

    private void assertMapperRegistered(DefaultListableBeanFactory registry, Class<?> mapperType) {
        assertTrue(registry.containsBeanDefinition(beanName(mapperType)), mapperType.getName());
    }

    private String beanName(Class<?> type) {
        String simpleName = type.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
