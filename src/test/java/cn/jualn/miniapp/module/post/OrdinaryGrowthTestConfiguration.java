package cn.jualn.miniapp.module.post;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.mockito.Mockito.mock;

/** Existing ordinary-content fixtures contain no growth associations; real growth behavior has its own database tests. */
@Configuration(proxyBeanMethods=false)
public class OrdinaryGrowthTestConfiguration {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.springframework.context.annotation.Import(OrdinaryGrowthTestConfiguration.class)
    public @interface Fixture {}
    @Bean cn.jualn.miniapp.module.post.service.GrowthRecordAccess growthAccess(){return mock(cn.jualn.miniapp.module.post.service.GrowthRecordAccess.class);}
    @Bean cn.jualn.miniapp.module.post.service.GrowthRecordService growthRecords(){return mock(cn.jualn.miniapp.module.post.service.GrowthRecordService.class);}
    @Bean cn.jualn.miniapp.module.media.service.GrowthMediaService growthMedia(){return mock(cn.jualn.miniapp.module.media.service.GrowthMediaService.class);}
    @Bean cn.jualn.miniapp.module.media.service.GrowthMediaAuthorization growthMediaAuthorization(){return mock(cn.jualn.miniapp.module.media.service.GrowthMediaAuthorization.class);}
    @Bean cn.jualn.miniapp.module.content.service.GrowthManagementTargets growthTargets(){return mock(cn.jualn.miniapp.module.content.service.GrowthManagementTargets.class);}
    @Bean cn.jualn.miniapp.module.notify.service.NotificationContentAccess ordinaryNotificationAccess(){return (user,type,id)->new cn.jualn.miniapp.module.notify.service.NotificationContentAccess.Decision(false,true,null);}
}
