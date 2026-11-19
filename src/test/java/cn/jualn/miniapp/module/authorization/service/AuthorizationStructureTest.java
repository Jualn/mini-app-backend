package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.lang.reflect.Modifier;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Mapper XML/type resolution and Spring registration; no database connections or SQL execution. */
class AuthorizationStructureTest {
    static MybatisConfiguration mappings() throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("structure", new SpringManagedTransactionFactory(), mock(DataSource.class)));
        for (String name : new String[]{"RbacMapper", "UnifiedMapper", "RbacOfflineMapper"}) {
            String path = "mapper/" + name + ".xml";
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("cn.jualn.miniapp.module.authorization.mapper")
    static class Config {
        @Bean SqlSessionFactory sessions() throws Exception { return new SqlSessionFactoryBuilder().build(mappings()); }
    }

    @Test void relocatedMapperNamespacesResolveTheirActualProjectionTypes() throws Exception {
        var configuration = mappings();
        assertTrue(configuration.hasMapper(RbacMapper.class));
        assertTrue(configuration.hasMapper(UnifiedMapper.class));
        assertTrue(configuration.hasMapper(RbacOfflineMapper.class));
        assertEquals(RbacModels.Subject.class, configuration.getMappedStatement(RbacMapper.class.getName() + ".subject")
                .getResultMaps().get(0).getType());
        assertEquals(RbacModels.AuthorityCoverage.class, configuration.getMappedStatement(RbacMapper.class.getName() + ".coverage")
                .getResultMaps().get(0).getType());
        assertEquals(RbacModels.InitialControlOrigin.class, configuration.getMappedStatement(RbacMapper.class.getName() + ".initialControlGrantOrigin")
                .getResultMaps().get(0).getType());
        assertEquals(UnifiedModels.AuthorityRow.class, configuration.getMappedStatement(UnifiedMapper.class.getName() + ".authority")
                .getResultMaps().get(0).getType());
        assertEquals(RbacOfflineTool.Environment.class, configuration.getMappedStatement(RbacOfflineMapper.class.getName() + ".environment")
                .getResultMaps().get(0).getType());
    }

    @Test void applicationMapperScanExcludesTheExplicitOfflineWriterAndExecutors() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertNotNull(context.getBean(RbacMapper.class));
            assertNotNull(context.getBean(UnifiedMapper.class));
            assertTrue(context.getBeansOfType(RbacOfflineMapper.class).isEmpty());
            assertTrue(context.getBeansOfType(RbacOfflineTool.class).isEmpty());
            assertTrue(context.getBeansOfType(RbacInitialMigration.class).isEmpty());
            assertTrue(context.getBeansOfType(RbacInitialCorrection.class).isEmpty());
            for (var executor : new Class<?>[]{RbacOfflineTool.class, RbacInitialMigration.class, RbacInitialCorrection.class, RbacRootOperations.class}) {
                assertFalse(org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                        executor, org.springframework.stereotype.Component.class), executor.getName());
            }
            assertFalse(RbacOfflineMapper.class.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class));
        }
    }

    @Test void snapshotAndProofCollaborationsRemainPackagePrivate() throws Exception {
        assertFalse(Modifier.isPublic(RbacAuthorizationReader.class.getDeclaredMethod("readCurrent", long.class,
                java.time.LocalDateTime.class).getModifiers()));
        assertFalse(Modifier.isPublic(RbacCatalog.class.getDeclaredConstructor(java.util.Set.class).getModifiers()));
        assertFalse(Modifier.isPublic(RbacOfflineAudit.class.getModifiers()));
        assertFalse(Modifier.isPublic(RbacOfflineMapper.class.getModifiers()));
        assertFalse(Modifier.isPublic(RbacJson.class.getModifiers()));
    }
}
