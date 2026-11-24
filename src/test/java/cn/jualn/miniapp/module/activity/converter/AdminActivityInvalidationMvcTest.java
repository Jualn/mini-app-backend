package cn.jualn.miniapp.module.activity.converter;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpInterface;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.exception.*;
import cn.jualn.miniapp.common.web.AdminQueryNoStoreFilter;
import cn.jualn.miniapp.module.activity.controller.admin.AdminActivityRegistrationController;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.bo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.LocalDateTime;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP adapter with explicit service/session doubles; MySQL behavior is proved separately. */
class AdminActivityInvalidationMvcTest {
    static final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final List<Object> evidence=new ArrayList<>();
    static final String path="/v1/admin/activities/7/registrations/3:invalidate",tag="\"activity-registration-3-v1\"";
    final ActivityRegistrationService service=mock(ActivityRegistrationService.class);
    MockMvc mvc;StpInterface previous;
    @BeforeEach void start(){
        previous=SaManager.getStpInterface();SaManager.setStpInterface(new StpInterface(){
            public List<String> getPermissionList(Object id,String type){return List.of("activity:registration:dispose");}
            public List<String> getRoleList(Object id,String type){return List.of();}
        });
        var storage=mock(cn.dev33.satoken.context.model.SaStorage.class);Map<String,Object> state=new HashMap<>();
        when(storage.get(anyString())).thenAnswer(i->state.get(i.getArgument(0)));when(storage.set(anyString(),any())).thenAnswer(i->{state.put(i.getArgument(0),i.getArgument(1));return storage;});when(storage.delete(anyString())).thenAnswer(i->{state.remove(i.getArgument(0));return storage;});
        SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);AdminStpUtil.STP_LOGIC.switchTo(9L);
        mvc=MockMvcBuilders.standaloneSetup(new AdminActivityRegistrationController(service,new AdminActivityRegistrationResourceConverter()))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(json)).addFilters(new AdminQueryNoStoreFilter()).build();
    }
    @AfterEach void stop(){SaManager.setStpInterface(previous);SaManager.getSaTokenContext().clearContext();}
    @AfterAll static void save() throws Exception {Files.createDirectories(Path.of("target/unified-v1"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/unified-v1/registration-http.json").toFile(),evidence);}
    void capture(MvcResult result) throws Exception {var response=result.getResponse();evidence.add(Map.of("method","POST","path",path,"status",response.getStatus(),"cacheControl",Objects.toString(response.getHeader("Cache-Control"),""),"body",json.readTree(response.getContentAsString())));}
    @Test void successUsesRegistrationEtagAndFirstDispositionProjection() throws Exception {
        var value=new ActivityRegistrationBO();value.setId(3L);value.setActivityId(7L);value.setUserId(11L);value.setStatus(2);value.setFormVersion("f1");value.setContractVersion(2L);value.setFormData(json.readTree("{\"name\":\"answer\"}"));
        var now=LocalDateTime.of(2026,11,3,10,0);value.setSubmittedAt(now);value.setUpdatedAt(now);value.setCancelledAt(now);value.setInvalidatedAt(now);value.setInvalidatedBy(9L);value.setInvalidReason("checked");
        when(service.invalidateContract(7L,3L,"checked",new RegistrationVersionBO(3,1))).thenReturn(value);
        var result=mvc.perform(post(path).header("If-Match",tag).contentType("application/json").content("{\"reason\":\"checked\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("ETag","\"activity-registration-3-v2\""))
            .andExpect(jsonPath("$.registrationEtag").value("\"activity-registration-3-v2\""))
            .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.invalidation.operatorUserId").value("9")).andReturn();capture(result);
        verify(service).invalidateContract(7L,3L,"checked",new RegistrationVersionBO(3,1));
    }
    @Test void activityEtagCannotBeUsedForARegistration() throws Exception {
        var result=mvc.perform(post(path).header("If-Match","\"activity-7-v1\"").contentType("application/json").content("{\"reason\":\"checked\"}"))
            .andExpect(status().isPreconditionFailed()).andExpect(header().string("Cache-Control","no-store")).andReturn();capture(result);verifyNoInteractions(service);
    }
    @Test void closedRequestDoesNotAcceptAnImpersonatedUserId() throws Exception {
        var result=mvc.perform(post(path).header("If-Match",tag).contentType("application/json").content("{\"reason\":\"checked\",\"userId\":\"12\"}"))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store")).andReturn();capture(result);verifyNoInteractions(service);
    }
    @Test void allDeclaredServiceFailuresAreNoStore() throws Exception {
        for(var code:List.of(org.springframework.http.HttpStatus.FORBIDDEN,org.springframework.http.HttpStatus.NOT_FOUND,org.springframework.http.HttpStatus.CONFLICT,org.springframework.http.HttpStatus.PRECONDITION_FAILED,org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)) {
            var failure=new ContractProblemException(code,code.value()==409?"/problems/registration-cancellation-closed":"/problems/service-unavailable","synthetic adapter boundary");
            doThrow(failure).when(service).invalidateContract(anyLong(),anyLong(),anyString(),any());
            var result=mvc.perform(post(path).header("If-Match",tag).contentType("application/json").content("{\"reason\":\"checked\"}"))
                .andExpect(status().is(code.value())).andExpect(header().string("Cache-Control","no-store")).andReturn();capture(result);
        }
    }
}
