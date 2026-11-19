package cn.jualn.miniapp.module.interact;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityInteractionSubject;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.service.PublicEventInteractionSubject;
import cn.jualn.miniapp.module.interact.service.InteractionSubjects;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.PostInteractionSubject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicitly owned V75 reminder schema; all fixture writes are rolled back. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/activity_reminder_test[0-9]*")
class InteractionProjectionDatabaseTest {
    @Test void subjectCapabilitiesAssembleWithoutBroadServicesAndKeepCountersInCallerTransaction() throws Exception {
        var source = new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        var db = new JdbcTemplate(source);
        assertEquals(Objects.requireNonNull(System.getProperty("boundary.test.serverUuid")),
                db.queryForObject("SELECT @@server_uuid",String.class));
        assertEquals("75",db.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
        var factory = new MybatisSqlSessionFactoryBean();
        var configuration = new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);factory.setDataSource(source);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()
                .setLogicNotDeleteValue("NULL").setLogicDeleteValue("NOW()")));
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var existence = sessions.getMapper(cn.jualn.miniapp.module.subject.mapper.SubjectExistenceMapper.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PostMapper.class,() -> sessions.getMapper(PostMapper.class));
            context.registerBean(ActivityMapper.class,() -> sessions.getMapper(ActivityMapper.class));
            context.registerBean(PublicEventMapper.class,() -> sessions.getMapper(PublicEventMapper.class));
            context.registerBean(RedisService.class,() -> mock(RedisService.class));
            context.register(PostInteractionSubject.class,ActivityInteractionSubject.class,
                    PublicEventInteractionSubject.class,InteractionSubjects.class);
            context.refresh();
            var subjects = context.getBean(InteractionSubjects.class);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            for (var type : List.of(TargetType.POST,TargetType.ACTIVITY,TargetType.EXAM)) {
                String table = switch (type) {case POST -> "post";case ACTIVITY -> "activity";default -> "public_event";};
                tx.executeWithoutResult(status -> {
                    String columns = type == TargetType.POST ? ",content" : "";
                    String values = type == TargetType.POST ? ",'synthetic body'" : "";
                    db.update("INSERT INTO " + table + "(id,user_id,title" + columns + ") VALUES(981217,981218,'synthetic title'" + values + ")");
                    var subject = subjects.require(type);
                    assertTrue(existence.exists(type.getCode(),981217));
                    assertEquals(java.util.Set.of(981217L),existence.existingIds(type.getCode(),List.of(981217L,981219L)));
                    assertEquals(981218L,subject.notificationSummary(981217).ownerId());
                    subject.commentApproved(981217);
                    subject.setLikeCount(981217,6);
                    assertEquals(1,subject.incrementViews(981217,5));
                    assertEquals(5L,subject.viewCount(981217));
                    assertEquals(5,subject.viewCounts(List.of(981217L)).get(981217L));
                    assertEquals(1,db.queryForObject("SELECT comment_count FROM " + table + " WHERE id=981217",Integer.class));
                    assertEquals(6,db.queryForObject("SELECT like_count FROM " + table + " WHERE id=981217",Integer.class));
                    db.update("UPDATE " + table + " SET deleted_at=NOW() WHERE id=981217");
                    assertFalse(existence.exists(type.getCode(),981217));
                    assertTrue(existence.existingIds(type.getCode(),List.of(981217L)).isEmpty());
                    status.setRollbackOnly();
                });
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=981217",Integer.class));
            }
        }
    }
}
