package cn.jualn.miniapp.module.officialcontent;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.officialcontent.bo.*;
import cn.jualn.miniapp.module.officialcontent.enums.*;
import cn.jualn.miniapp.module.officialcontent.controller.*;
import cn.jualn.miniapp.module.officialcontent.controller.admin.*;
import cn.jualn.miniapp.module.officialcontent.converter.*;
import cn.jualn.miniapp.module.officialcontent.service.*;
import cn.jualn.miniapp.module.officialcontent.service.impl.*;
import cn.jualn.miniapp.module.officialcontent.mapper.*;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.service.*;
import cn.jualn.miniapp.module.media.service.impl.*;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL, Mapper, transaction proxy and MVC; administrator request identity and COS are test doubles. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/official_content_test")
class OfficialContentDatabaseTest {
    AnnotationConfigApplicationContext context;
    OfficialContentService service;
    MediaService media;
    JdbcTemplate jdbc;
    MockMvc mvc;
    long image;
    long video;
    boolean allowed = true;
    cn.dev33.satoken.stp.StpInterface previous;
    final ObjectMapper json = new ObjectMapper().addMixIn(org.springframework.http.ProblemDetail.class,
            org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(OfficialContentService.class); media = context.getBean(MediaService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update("DELETE FROM event_attachment_link WHERE target_type=7");
        jdbc.update("DELETE FROM official_content");
        previous = cn.dev33.satoken.SaManager.getStpInterface();
        cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
            public List<String> getPermissionList(Object id, String type) { return allowed && "admin".equals(type) ? List.of("content:read", "content:manage") : List.of(); }
            public List<String> getRoleList(Object id, String type) { return List.of(); }
        });
        identity();
        image = media.registerAttachment("IMAGE", "党建图片", "https://outside.example/image.jpg", 9L).getId();
        video = media.registerAttachment("LINK", "宣传视频", "https://outside.example/video.mp4", 9L).getId();
        var converter = new OfficialContentConverter();
        mvc = MockMvcBuilders.standaloneSetup(new OfficialContentController(service, converter), new AdminOfficialContentController(service, converter))
                .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    void identity() {
        var storage = mock(cn.dev33.satoken.context.model.SaStorage.class);
        Map<String,Object> values = new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i -> values.get(i.getArgument(0)));
        when(storage.set(anyString(),any())).thenAnswer(i -> { values.put(i.getArgument(0),i.getArgument(1)); return storage; });
        when(storage.delete(anyString())).thenAnswer(i -> { values.remove(i.getArgument(0)); return storage; });
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
        AdminStpUtil.STP_LOGIC.switchTo(9L);
    }
    @AfterEach void cleanup() {
        if (previous != null) { cn.dev33.satoken.SaManager.setStpInterface(previous); cn.dev33.satoken.SaManager.getSaTokenContext().clearContext(); }
        if (context != null) context.close();
    }
    OfficialContentSaveBO command(OfficialContentStatus status) {
        return new OfficialContentSaveBO("党建资讯", "摘要", "正文\n第二段", "信息学科部党支部", OfficialContentCategory.NEWS, status, List.of(image), video);
    }
    String body(OfficialContentStatus status) throws Exception {
        return json.writeValueAsString(Map.of("title","党建资讯","summary","摘要","body","正文\n第二段","publisher","信息学科部党支部","category","NEWS","publishStatus",status.name(),"imageAttachmentIds",List.of(Long.toString(image)),"videoAttachmentId",Long.toString(video)));
    }
    String etag(OfficialContentBO value) { return StrongEtag.of("official-content",value.id(),value.revision()); }

    @Test void createPublishReadRemoveVideoAndUnpublishThroughMvc() throws Exception {
        var create = mvc.perform(post("/v1/admin/official-contents").contentType("application/json").content(body(OfficialContentStatus.DRAFT)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.publishStatus").value("DRAFT"))
                .andExpect(header().exists("ETag")).andExpect(header().exists("Location")).andReturn();
        long id = json.readTree(create.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/v1/official-contents/"+id)).andExpect(status().isNotFound());
        var published = mvc.perform(put("/v1/admin/official-contents/"+id).header("If-Match",create.getResponse().getHeader("ETag")).contentType("application/json").content(body(OfficialContentStatus.PUBLISHED)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.video.kind").value("VIDEO")).andReturn();
        mvc.perform(get("/v1/official-contents/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.images[0].attachmentId").value(Long.toString(image))).andExpect(jsonPath("$.body").value("正文\n第二段"));
        var entity = service.get(id,true); var first = entity.publishedAt();
        var noVideo = new OfficialContentSaveBO("已编辑","摘要","新正文","信息学科部党支部",OfficialContentCategory.E_SAFETY,OfficialContentStatus.PUBLISHED,List.of(image),null);
        entity = service.replace(id,noVideo,etag(entity));
        assertNull(jdbc.queryForObject("SELECT video_attachment_id FROM official_content WHERE id=?",Long.class,id));
        assertNull(service.get(id,false).video()); assertEquals(first,entity.publishedAt());
        entity = service.replace(id,command(OfficialContentStatus.UNPUBLISHED),etag(entity));
        mvc.perform(get("/v1/official-contents/"+id)).andExpect(status().isNotFound());
        assertThrows(ContractProblemException.class,() -> service.replace(id,command(OfficialContentStatus.DRAFT),etag(service.get(id,true))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_attachment WHERE id=?",Integer.class,image));
        var republished = service.replace(id,command(OfficialContentStatus.PUBLISHED),etag(entity));
        assertEquals(first,republished.publishedAt());
    }
    @Test void revisionsAndMissingVersionNeverOverwrite() throws Exception {
        var entity = service.create(command(OfficialContentStatus.DRAFT));
        var newer = service.replace(entity.id(),command(OfficialContentStatus.PUBLISHED),etag(entity));
        mvc.perform(put("/v1/admin/official-contents/"+entity.id()).header("If-Match",etag(entity)).contentType("application/json").content(body(OfficialContentStatus.UNPUBLISHED)))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.type").value("/problems/revision-mismatch"));
        mvc.perform(put("/v1/admin/official-contents/"+entity.id()).contentType("application/json").content(body(OfficialContentStatus.UNPUBLISHED))).andExpect(status().isPreconditionRequired());
        assertEquals(newer.revision(),service.get(entity.id(),true).revision());
        assertEquals("PUBLISHED",service.get(entity.id(),true).publishStatus());
    }
    @Test void unregisteredReferenceRollsBackSubjectAndLinks() {
        var entity = service.create(command(OfficialContentStatus.DRAFT));
        jdbc.update("INSERT INTO media_attachment(type,kind,registered,url,original_name) VALUES(1,'IMAGE',0,'https://outside.example/legacy.jpg','legacy')");
        Long legacy = jdbc.queryForObject("SELECT MAX(id) FROM media_attachment",Long.class);
        var invalid = new OfficialContentSaveBO("不应保存","","不应保存","党支部",OfficialContentCategory.NEWS,OfficialContentStatus.PUBLISHED,List.of(legacy),null);
        assertThrows(ContractProblemException.class,() -> service.replace(entity.id(),invalid,etag(entity)));
        var saved = service.get(entity.id(),true);
        assertEquals("DRAFT",saved.publishStatus()); assertEquals(entity.revision(),saved.revision()); assertEquals("党建资讯",saved.title());
        assertEquals(image,saved.images().get(0).getId()); assertNotNull(saved.video());
    }
    @Test void permissionsAreServiceOwnedAndCallerCannotPublishOnCreate() throws Exception {
        mvc.perform(post("/v1/admin/official-contents").contentType("application/json").content(body(OfficialContentStatus.PUBLISHED))).andExpect(status().isBadRequest());
        allowed=false;
        mvc.perform(post("/v1/admin/official-contents").contentType("application/json").content(body(OfficialContentStatus.DRAFT))).andExpect(status().isForbidden());
        assertThrows(cn.dev33.satoken.exception.NotPermissionException.class,() -> service.list(null,null,null,20,true));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM official_content",Integer.class));
    }
    @Test void categoriesAreFilteredBeforePagingAndCursorsBindFilters() {
        for (int i=0;i<3;i++) { var v=service.create(command(OfficialContentStatus.DRAFT)); service.replace(v.id(),command(OfficialContentStatus.PUBLISHED),etag(v)); }
        var page = service.list(OfficialContentCategory.NEWS,null,null,1,false);
        assertEquals(1,page.items().size()); assertTrue(page.hasMore());
        assertEquals(1,service.list(OfficialContentCategory.NEWS,null,page.nextCursor(),1,false).items().size());
        assertTrue(service.list(OfficialContentCategory.INFO_RED,null,null,1,false).items().isEmpty());
        assertThrows(ContractProblemException.class,() -> service.list(OfficialContentCategory.INFO_RED,null,page.nextCursor(),1,false));
        assertThrows(ContractProblemException.class,() -> service.list(OfficialContentCategory.NEWS,null,page.nextCursor(),1,true));
    }
    @Test void managedOfficialUploadBindsOnRegistrationAndRejectsOtherOperator() {
        assertThrows(BusinessException.class, () -> media.generateUploadCredential(TargetType.OFFICIAL_CONTENT, List.of("video.mp4")));
        String key = "official-content/9/"+UUID.randomUUID()+".mp4";
        jdbc.update("INSERT INTO media_upload_record(user_id,target_type,object_key,status,cleanup_after) VALUES(9,7,?,0,DATE_ADD(NOW(),INTERVAL 1 HOUR))",key);
        assertThrows(BusinessException.class,() -> media.registerAttachment("LINK","视频","https://cdn.example/"+key,10L));
        var attachment = media.registerAttachment("LINK","视频","https://cdn.example/"+key,9L);
        assertEquals(1,jdbc.queryForObject("SELECT status FROM media_upload_record WHERE object_key=?",Integer.class,key));
        assertEquals(attachment.getId(),jdbc.queryForObject("SELECT bound_attachment_id FROM media_upload_record WHERE object_key=?",Long.class,key));
        assertNull(jdbc.queryForObject("SELECT bound_target_id FROM media_upload_record WHERE object_key=?",Long.class,key));
    }
    @Test void twoEditorsWithSameVersionHaveOneWinner() throws Exception {
        var entity=service.create(command(OfficialContentStatus.DRAFT));
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        Callable<Integer> attempt=() -> {
            identity(); start.await();
            try { service.replace(entity.id(),command(OfficialContentStatus.PUBLISHED),etag(entity)); return 200; }
            catch (ContractProblemException ex) { return ex.getStatus().value(); }
            finally { cn.dev33.satoken.SaManager.getSaTokenContext().clearContext(); }
        };
        try { var a=pool.submit(attempt); var b=pool.submit(attempt); start.countDown(); assertEquals(Set.of(200,412),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))); }
        finally { pool.shutdownNow(); }
        assertEquals(2,service.get(entity.id(),true).revision());
    }
    @Test void newReferenceTypeDoesNotChangeOldData() {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE title='旧帖子'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM event_attachment_link WHERE target_type=2 AND target_id=123",Integer.class));
    }

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean cn.jualn.miniapp.common.mapper.EnumConverter enums() { return new cn.jualn.miniapp.common.mapper.EnumConverter(); }
        @Bean MediaConverter converter() { return Mappers.getMapper(MediaConverter.class); }
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getProperty("event.test.jdbcUrl")+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",""); }
        @Bean DataSourceTransactionManager transactions(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean SqlSessionFactory sqlSessions(DataSource data) throws Exception {
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
            var configuration=new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration);
            var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
            global.setMetaObjectHandler(new cn.jualn.miniapp.config.MybatisPlusConfig().metaObjectHandler());
            factory.setGlobalConfig(global);
            factory.setMapperLocations(new ClassPathResource("mapper/OfficialContentMapper.xml"),new ClassPathResource("mapper/MediaAttachmentMapper.xml"),new ClassPathResource("mapper/MediaUploadRecordMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<OfficialContentMapper> contents(SqlSessionFactory sessions) { var bean=new MapperFactoryBean<>(OfficialContentMapper.class);bean.setSqlSessionFactory(sessions);return bean; }
        @Bean MapperFactoryBean<MediaAttachmentMapper> attachments(SqlSessionFactory sessions) { var bean=new MapperFactoryBean<>(MediaAttachmentMapper.class);bean.setSqlSessionFactory(sessions);return bean; }
        @Bean MapperFactoryBean<MediaUploadRecordMapper> records(SqlSessionFactory sessions) { var bean=new MapperFactoryBean<>(MediaUploadRecordMapper.class);bean.setSqlSessionFactory(sessions);return bean; }
        @Bean CosClient cos() { var cos=mock(CosClient.class); when(cos.buildPublicUrl(anyString())).thenAnswer(i -> "https://cdn.example/"+i.getArgument(0));return cos; }
        @Bean CosService cosService(CosClient cos) { return new CosService(cos,new CosProperties("unused","unused","campus-123","ap-test","https://cdn.example",600,"cdn.example"),new ObjectMapper()); }
        @Bean MediaUploadRecordService uploads(MediaUploadRecordMapper records,CosService cos) { return new MediaUploadRecordService(records,cos); }
        @Bean MediaService media(MediaAttachmentMapper attachments,CosService cos,MediaUploadRecordService uploads,MediaConverter converter) { return new MediaServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), converter,attachments,mock(SubjectExistenceService.class),cos,uploads,org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)); }
        @Bean OfficialContentService officialContent(OfficialContentMapper mapper,MediaService media,cn.jualn.miniapp.module.media.service.MediaQueries queries) { return new OfficialContentServiceImpl(org.mockito.Mockito.mock(cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class), mapper,media,queries,org.mockito.Mockito.mock(cn.jualn.miniapp.module.officialcontent.service.OfficialPublicationWriter.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.officialcontent.service.OfficialContentSafetyChecks.class)); }
  @Bean cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries(MediaConverter c,MediaAttachmentMapper a,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaQueriesService(c,a,
      org.mockito.Mockito.mock(cn.jualn.miniapp.module.subject.service.SubjectExistenceService.class),
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }
  @Bean cn.jualn.miniapp.module.media.service.MediaAccess mediaAccess(MediaAttachmentMapper a,
      cn.jualn.miniapp.module.media.service.MediaQueries q,MediaUploadRecordService uploads,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.authorization.service.CurrentAuthorization> auth,
      org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.media.service.ArticleMediaService> article) {
    return new cn.jualn.miniapp.module.media.service.MediaAccessService(auth.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.authorization.service.CurrentAuthorization.class)),a,q,uploads,
      article.getIfAvailable(()->org.mockito.Mockito.mock(cn.jualn.miniapp.module.media.service.ArticleMediaService.class)));
  }

    }
}
