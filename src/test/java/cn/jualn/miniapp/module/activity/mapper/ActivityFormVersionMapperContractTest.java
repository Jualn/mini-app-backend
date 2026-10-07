package cn.jualn.miniapp.module.activity.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ActivityFormVersionMapperContractTest {
    @Test
    void formVersionWritesAreBackendOwnedAndNullProtected() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/not-used"));
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"));
        factory.getObject();

        String initialize = configuration.getMappedStatement(ActivityMapper.class.getName()
                        + ".initializeFormVersionIfAbsent")
                .getBoundSql(Map.of("id", 17L, "formVersion", "activity-17-form-v1")).getSql();
        assertTrue(initialize.contains("form_version = ?"));
        assertTrue(initialize.contains("form_version IS NULL"));

        String publish = configuration.getMappedStatement(ActivityMapper.class.getName() + ".publishDirectly")
                .getBoundSql(Map.of("id", 17L, "formVersion", "activity-17-form-v1")).getSql();
        assertTrue(publish.contains("form_version = COALESCE(form_version, ?)"));
        assertTrue(!publish.contains("CONCAT('activity-'"));

        for (String statement : new String[]{"selectAdminActivityById", "selectByIdNotDeleted", "selectRegistrationActivity"}) {
            String read = configuration.getMappedStatement(ActivityMapper.class.getName() + "." + statement)
                    .getBoundSql(Map.of("activityId", 17L, "id", 17L)).getSql();
            assertTrue(read.contains("form_version"), statement + " must read the persisted form version");
        }
    }
}
