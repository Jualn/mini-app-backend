package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.activity.mapper.*;
import cn.jualn.miniapp.module.activity.service.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse;

class ActivityRegistrationDatabaseTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    ActivityMapper activityMapper;
    ActivityRegistrationMapper mapper;
    ActivityRegistrationService service;
    long activityId;
    long user1;
    long user2;
    cn.dev33.satoken.stp.StpInterface previousPermissions;
    boolean allowAdmin;
    final String schema = ActivityFormPolicyTest.SCHEMA;
    final String data = "{\"student\":\"001\",\"c\":true}";

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("event.test.jdbcUrl");
        Assumptions.assumeTrue(url != null, "Disposable V1..V7 database required");
        var ds = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig()
                .setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig().setLogicNotDeleteValue("NULL").setLogicDeleteValue("NOW()"))
                .setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/ActivityMapper.xml"), new ClassPathResource("mapper/ActivityRegistrationMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        activityMapper = session.getMapper(ActivityMapper.class); mapper = session.getMapper(ActivityRegistrationMapper.class);
        var proxy = new ProxyFactory(new ActivityRegistrationServiceImpl(activityMapper, mapper, new ActivityFormAvailability(true)));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ActivityRegistrationService) proxy.getProxy();
        user1 = newUser(); user2 = newUser();
        jdbc.update("INSERT INTO activity(user_id,title,content,registration_mode,participant_mode,publish_status,form_schema,registration_limit,registration_end,registration_end_precision) VALUES(?,'form test','text',2,1,1,?,1,DATE_ADD(NOW(),INTERVAL 1 DAY),2)", user1, schema);
        activityId = jdbc.queryForObject("SELECT MAX(id) FROM activity", Long.class);
        UserContext.setUserId(user1);
        // Request storage supplies a test identity; the real service still performs Sa-Token permission checks.
        var storage = mock(cn.dev33.satoken.context.model.SaStorage.class);
        Map<String,Object> values = new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i -> values.get(i.getArgument(0)));
        when(storage.set(anyString(), any())).thenAnswer(i -> { values.put(i.getArgument(0), i.getArgument(1)); return storage; });
        when(storage.delete(anyString())).thenAnswer(i -> { values.remove(i.getArgument(0)); return storage; });
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class), mock(cn.dev33.satoken.context.model.SaResponse.class), storage);
        previousPermissions = cn.dev33.satoken.SaManager.getStpInterface();
        cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object id, String type) { return allowAdmin && type.equals("admin") ? List.of("activity:read", "activity:edit") : List.of(); }
            public List<String> getRoleList(Object id, String type) { return List.of(); }
        });
        AdminStpUtil.STP_LOGIC.switchTo(9L); allowAdmin = true;
    }
    @AfterEach void cleanup() {
        UserContext.clear();
        if (previousPermissions != null) {
            cn.dev33.satoken.SaManager.setStpInterface(previousPermissions);
            cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();
        }
    }
    long newUser() {
        jdbc.update("INSERT INTO user_profile(openid) VALUES(?)", "form-test-" + UUID.randomUUID());
        return jdbc.queryForObject("SELECT MAX(id) FROM user_profile", Long.class);
    }
    @Test void repeatCancelRestoreAndInvalidationKeepOneRow() {
        long id = service.submit(activityId, parse(data)).getId();
        assertEquals(id, service.submit(activityId, parse(data)).getId());
        assertThrows(BusinessException.class, () -> service.submit(activityId, parse(data.replace("001", "002"))));
        service.cancelMine(activityId); service.cancelMine(activityId);
        assertEquals(0, service.getForm(activityId).submittedCount());
        assertEquals(id, service.submit(activityId, parse(data.replace("001", "002"))).getId());
        assertNull(service.getMine(activityId).getCancelledAt());
        service.invalidate(activityId, id, "重复信息"); service.invalidate(activityId, id, "保留首次原因");
        assertEquals("重复信息", service.getMine(activityId).getInvalidReason());
        assertEquals(9L, service.getMine(activityId).getInvalidatedBy());
        assertThrows(BusinessException.class, () -> service.submit(activityId, parse(data)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM activity_registration WHERE activity_id=?", Integer.class, activityId));
    }
    @Test void lastPlaceCannotBeOversold() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long userId : new long[]{user1,user2}) results.add(pool.submit(() -> {
                UserContext.setUserId(userId);
                try { start.await(); service.submit(activityId, parse(data)); return true; }
                catch (BusinessException e) { return false; }
                finally { UserContext.clear(); }
            }));
            start.countDown();
            int successes = 0; for (var r : results) if (r.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, service.getForm(activityId).submittedCount());
        } finally { pool.shutdownNow(); }
    }
    @Test void firstSubmissionSerializesWithSchemaEditAndFreezesEvenAfterCancel() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                UserContext.setUserId(user1);
                try { new TransactionTemplate(transactions).execute(s -> {
                    activityMapper.lockRegistrationActivity(activityId); locked.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    service.submit(activityId, parse(data)); return null;
                }); } finally { UserContext.clear(); }
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> edit = pool.submit(() -> new TransactionTemplate(transactions).execute(s -> {
                var a = activityMapper.selectForUpdate(activityId);
                service.validateFormChange(activityId, a.getFormSchema(), schema.replace("学号", "改名"), 1, 2, 2, 1); return null;
            }));
            assertThrows(TimeoutException.class, () -> edit.get(150, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertInstanceOf(BusinessException.class, assertThrows(ExecutionException.class, () -> edit.get(5, TimeUnit.SECONDS)).getCause());
            service.cancelMine(activityId);
            assertTrue(service.getForm(activityId).frozen());
            assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).execute(s -> {
                var a = activityMapper.selectForUpdate(activityId);
                service.validateFormChange(activityId, a.getFormSchema(), schema.replace("学号", "改名"), 1, 2, 2, 1); return null;
            }));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void cutoffAndHiddenActivityStillAllowHistoryAndCancellation() {
        long id = service.submit(activityId, parse(data)).getId();
        jdbc.update("UPDATE activity SET registration_end=NOW() WHERE id=?", activityId);
        assertEquals(id, service.submit(activityId, parse(data)).getId());
        service.cancelMine(activityId);
        assertThrows(BusinessException.class, () -> service.submit(activityId, parse(data)));
        jdbc.update("UPDATE activity SET publish_status=3,deleted_at=NOW() WHERE id=?", activityId);
        assertEquals(2, service.getMine(activityId).getStatus());
        assertThrows(BusinessException.class, () -> service.getForm(activityId));
    }
    @Test void draftDownCancelledAndBeforeStartRejectNewSubmissions() {
        for (int status : new int[]{0,2,3}) {
            jdbc.update("UPDATE activity SET publish_status=? WHERE id=?", status, activityId);
            assertThrows(BusinessException.class, () -> service.submit(activityId, parse(data)));
        }
        jdbc.update("UPDATE activity SET publish_status=1,registration_start=DATE_ADD(NOW(),INTERVAL 1 HOUR) WHERE id=?", activityId);
        assertThrows(BusinessException.class, () -> service.submit(activityId, parse(data)));
    }
    @Test void userCannotReadOtherAnswersOrManage() {
        long id = service.submit(activityId, parse(data)).getId();
        UserContext.setUserId(user2);
        assertNull(service.getMine(activityId));
        assertThrows(BusinessException.class, () -> service.cancelMine(activityId));
        allowAdmin = false;
        assertThrows(cn.dev33.satoken.exception.NotPermissionException.class, () -> service.pageAdmin(activityId, null, null, 20));
        assertThrows(cn.dev33.satoken.exception.NotPermissionException.class, () -> service.getAdminRegistration(activityId, id));
        assertThrows(cn.dev33.satoken.exception.NotPermissionException.class, () -> service.export(activityId, null));
        assertThrows(cn.dev33.satoken.exception.NotPermissionException.class, () -> service.invalidate(activityId, id, "test"));
    }
    @Test void listExportStatusAndActivityOwnershipMatch() throws Exception {
        long id = service.submit(activityId, parse(data)).getId();
        assertThrows(BusinessException.class, () -> service.getAdminRegistration(activityId + 999999, id));
        var page = service.pageAdmin(activityId, 1, null, 20);
        assertEquals(1, page.items().size()); assertNull(page.items().get(0).getFormData());
        service.cancelMine(activityId);
        assertTrue(service.pageAdmin(activityId, 1, null, 20).items().isEmpty());
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(activityId, 2)))) {
            assertEquals(2, book.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("001", book.getSheetAt(0).getRow(1).getCell(10).getStringCellValue());
        }
    }
    @Test void limitsAndDatabaseUniqueConstraintAreEnforced() {
        service.submit(activityId, parse(data));
        jdbc.update("UPDATE activity SET registration_limit=2 WHERE id=?", activityId);
        UserContext.setUserId(user2); service.submit(activityId, parse(data));
        assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).execute(s -> {
            var a = activityMapper.selectForUpdate(activityId);
            service.validateFormChange(activityId, a.getFormSchema(), a.getFormSchema(), 1, 2, 2, 1); return null;
        }));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("INSERT INTO activity_registration(activity_id,user_id,form_data,status,submitted_at) VALUES(?,?,?,1,NOW())", activityId, user1, data));
    }
    @Test void rollbackDoesNotConsumeSlotOrFreezeForm() {
        new TransactionTemplate(transactions).execute(s -> { service.submit(activityId, parse(data)); s.setRollbackOnly(); return null; });
        assertEquals(0, service.getForm(activityId).submittedCount());
        assertFalse(service.getForm(activityId).frozen());
        assertNotNull(service.submit(activityId, parse(data)));
    }
    @Test void disabledGateRejectsSubmissionButAllowsCancellation() {
        service.submit(activityId, parse(data));
        var closed = new ActivityRegistrationServiceImpl(activityMapper, mapper, new ActivityFormAvailability(false));
        assertThrows(BusinessException.class, () -> closed.submit(activityId, parse(data)));
        assertNotNull(closed.getMine(activityId));
        service.cancelMine(activityId);
    }
    ActivityService activityOwner(boolean enabled) {
        var content = mock(cn.jualn.miniapp.module.eventcontent.service.EventContentService.class);
        when(content.saveSections(any(), anyLong(), any(), any())).thenAnswer(i -> i.getArgument(3));
        var owner = new ActivityServiceImpl(content, activityMapper,
                mock(cn.jualn.miniapp.module.activity.converter.ActivityConverter.class), mock(ActivityEnrollmentService.class),
                mock(cn.jualn.miniapp.module.media.service.MediaService.class), mock(cn.jualn.miniapp.module.user.service.UserService.class),
                mock(cn.jualn.miniapp.module.timeline.service.TimelineService.class), mock(cn.jualn.miniapp.infrastructure.cache.RedisService.class),
                mock(cn.jualn.miniapp.module.interact.service.InteractService.class), mock(cn.jualn.miniapp.module.notify.service.NotifyService.class),
                service, new ActivityFormAvailability(enabled));
        var proxy = new ProxyFactory(owner);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ActivityService) proxy.getProxy();
    }
    cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO command() {
        return cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO.builder()
                .operatorId(user1).title("个人表单测试").organizer("运维").content("表单介绍")
                .category(cn.jualn.miniapp.common.enums.ActivityCategory.OTHER).audienceScope(1)
                .registrationMode(2).participantMode(1).formSchema(parse(schema)).registrationLimit(2)
                .registrationEnd(java.time.LocalDateTime.now().plusDays(1)).registrationEndPrecision(2).build();
    }
    @Test void adminSavePublishAndEditUseSameFreezeAndLimitRules() {
        ActivityService owner = activityOwner(true);
        var command = command();
        long id = owner.createAdminActivity(command); command.setId(id);
        assertEquals(parse(schema), parse(activityMapper.selectForUpdate(id).getFormSchema()));
        assertThrows(BusinessException.class, () -> activityOwner(false).publishAdminActivity(id, user1));
        owner.publishAdminActivity(id, user1);
        service.submit(id, parse(data));
        command.setFormSchema(null); command.setRegistrationLimit(null); command.setLocation("新地点");
        owner.updateAdminActivity(command);
        assertEquals(2, activityMapper.selectForUpdate(id).getRegistrationLimit());
        assertEquals("新地点", activityMapper.selectForUpdate(id).getLocation());
        assertEquals(parse(schema), parse(activityMapper.selectForUpdate(id).getFormSchema()));
        command.setFormSchema(parse(schema.replace("学号", "修改标题")));
        assertThrows(BusinessException.class, () -> owner.updateAdminActivity(command));
        command.setFormSchema(null); command.setRegistrationMode(3);
        assertThrows(BusinessException.class, () -> owner.updateAdminActivity(command));
        command.setRegistrationMode(2); command.setClearRegistrationLimit(true);
        owner.updateAdminActivity(command);
        assertNull(activityMapper.selectForUpdate(id).getRegistrationLimit());
    }
    @Test void publishedFormCannotLoseDefinitionOrUseTeamDateOnlyWindow() {
        ActivityService owner = activityOwner(true); var command = command();
        long id = owner.createAdminActivity(command); command.setId(id); owner.publishAdminActivity(id, user1);
        command.setFormSchema(com.fasterxml.jackson.databind.node.NullNode.instance);
        assertThrows(BusinessException.class, () -> owner.updateAdminActivity(command));
        assertNotNull(activityMapper.selectForUpdate(id).getFormSchema());
        command.setFormSchema(parse(schema)); command.setParticipantMode(2);
        assertThrows(BusinessException.class, () -> owner.updateAdminActivity(command));
        command.setParticipantMode(1); command.setRegistrationEnd(java.time.LocalDateTime.now().plusDays(1).toLocalDate().atStartOfDay()); command.setRegistrationEndPrecision(1);
        assertThrows(BusinessException.class, () -> owner.updateAdminActivity(command));
    }
    @Test void semanticallySameAnswersRetryWithoutNewPlace() {
        var first = service.submit(activityId, parse("{\"student\":\"001\",\"c\":true,\"n\":1,\"m\":[\"b\",\"a\"],\"note\":null}"));
        var retry = service.submit(activityId, parse("{\"m\":[\"a\",\"b\"],\"n\":1.0,\"c\":true,\"student\":\"001\"}"));
        assertEquals(first.getId(), retry.getId());
        assertEquals(1, service.getForm(activityId).submittedCount());
    }
    @Test void exportRejectsOversizeInsteadOfTruncatingAndChecksRecordParent() {
        long id = service.submit(activityId, parse(data)).getId();
        jdbc.update("INSERT INTO activity(user_id,title,content) VALUES(?,'other','text')", user1);
        long other = jdbc.queryForObject("SELECT MAX(id) FROM activity", Long.class);
        assertThrows(BusinessException.class, () -> service.getAdminRegistration(other, id));
        var mockMapper = mock(ActivityRegistrationMapper.class);
        var exporter = new ActivityRegistrationServiceImpl(activityMapper, mockMapper, new ActivityFormAvailability(true));
        var row = new cn.jualn.miniapp.module.activity.entity.ActivityRegistration(); row.setFormData(data);
        when(mockMapper.exportRows(activityId, null, 1001)).thenReturn(Collections.nCopies(1001, row));
        assertThrows(BusinessException.class, () -> exporter.export(activityId, null));
        row.setFormData("x".repeat(10000));
        when(mockMapper.exportRows(activityId, null, 1001)).thenReturn(Collections.nCopies(1000, row));
        assertThrows(BusinessException.class, () -> exporter.export(activityId, null));
    }
}
