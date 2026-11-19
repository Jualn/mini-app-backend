package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;

import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;

import java.time.LocalDateTime;

/**
 * Offline preparation only. Reuses the existing initial migration's controlled ROOT support.
 * Does not register an application Bean, enable capabilities, or grant an Assignment.
 */
public final class RbacInitialControlVerifier {

    private final RbacMapper mapper;
    private final RbacAuthorizationReader reader;

    public RbacInitialControlVerifier(javax.sql.DataSource source) throws Exception {
        // Separate owner Mapper session factory, SAME DataSource and caller transaction/connection.
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setCacheEnabled(false);
        config.addMapper(RbacMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
        global.setBanner(false);
        factory.setGlobalConfig(global);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/RbacMapper.xml"));
        this.mapper = new org.mybatis.spring.SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject())).getMapper(RbacMapper.class);
        reader = new RbacAuthorizationReader(mapper, new RbacCatalog(RbacCatalog.LEARNING_TASK_INTEGRATION_SUPPORT), new AdminPermissionPolicy());
    }

    public void lock(long... users) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Existing owner transaction required");
        if (!"ROOT".equals(mapper.lockGovernance())) throw RbacProblems.unavailable();
        java.util.Arrays.stream(users).distinct().sorted().forEach(id -> {
            var subject = mapper.lockSubject(id);
            if (subject == null || subject.getDeletedAt() != null)
                throw RbacProblems.problem(org.springframework.http.HttpStatus.NOT_FOUND, "not-found");
            if (subject.getSource() == null || subject.getVersion() == null) throw RbacProblems.unavailable();
        });
    }

    public RbacModels.Decision requireRoot(long actor, LocalDateTime at) {
        var result = reader.readCurrent(actor, at);
        if (result.grants().stream().noneMatch(g -> "ROOT".equals(g.roleCode()) && g.allows("authorization:root-manage", "AUTHORIZATION") && g.allows("authorization:assignment-manage", "AUTHORIZATION")))
            throw RbacProblems.problem(org.springframework.http.HttpStatus.FORBIDDEN, "authorization-management-forbidden");
        for (var a : mapper.currentAssignments(actor, at)) if ("ROOT".equals(a.getRoleCode())) verifyOrigin(a);
        return result;
    }

    public String requireAssignmentAccount(long user, LocalDateTime at) {
        var subject = mapper.subject(user);
        if (subject == null || !"ASSIGNMENT".equals(subject.getSource()) || subject.getVersion() == null || subject.getDeletedAt() != null || !Integer.valueOf(1).equals(subject.getStatus()))
            throw RbacProblems.unavailable();
        // Validate every current assignment's offline origin and catalog even if currently NOT_READY.
        reader.readCurrent(user, at);
        for (var a : mapper.currentAssignments(user, at)) verifyOrigin(a);
        return subject.getVersion();
    }

    private void verifyOrigin(RbacModels.Assignment assignment) {
        var origin = mapper.initialControlGrantOrigin(assignment);
        if (origin == null || origin.ordinaryGrants() + origin.offlineGrants() != 1) throw RbacProblems.unavailable();
    }
}
