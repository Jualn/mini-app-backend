package cn.jualn.miniapp.module.exam.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HomePublicMatterReminderMapperContractTest {

    @Test
    void mapperXmlParsesAndBuildsSingleSnapshotQuery() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/not-used"));
        factory.setMapperLocations(new ClassPathResource("mapper/ExamInfoMapper.xml"));
        factory.getObject();

        String statementId = ExamInfoMapper.class.getName() + ".selectHomePublicMatterReminders";
        String sql = configuration.getMappedStatement(statementId)
                .getBoundSql(Map.of(
                        "userId", 42L,
                        "evaluatedAt", LocalDateTime.of(2026, 9, 12, 10, 0),
                        "limit", 5))
                .getSql();

        assertTrue(sql.contains("WITH future_nodes AS"));
        assertTrue(sql.contains("INNER JOIN timeline node"));
        assertTrue(sql.contains("FROM public_event event"));
        assertTrue(sql.contains("node.label AS nodeName"));
        assertTrue(sql.contains("node.start_precision = 2"));
        assertTrue(sql.contains("node.end_time IS NULL"));
        assertTrue(sql.contains("node.end_precision = 0"));
        assertTrue(!sql.contains("'事项开始' AS nodeName"));
        assertTrue(!sql.contains("event.start_time AS reminderAt"));
        assertTrue(!sql.contains("event.status"));
        assertTrue(sql.contains("ROW_NUMBER() OVER"));
        assertTrue(sql.contains("PARTITION BY node.publicMatterId"));
        assertTrue(sql.contains("WHERE node.nodeRank = 1"));
        assertTrue(sql.contains("subscribed_nodes AS"));
        assertTrue(sql.contains("'SUBSCRIPTIONS' AS source"));
        assertTrue(sql.contains("'DEFAULT' AS source"));
        assertTrue(sql.contains("NOT EXISTS"));
        assertTrue(sql.contains("ORDER BY candidate.reminderAt ASC"));
        assertTrue(sql.contains("COALESCE(candidate.timeNodeId, 0) ASC"));
        assertTrue(sql.contains("LIMIT ?"));
    }
}
