package cn.jualn.miniapp.module.user.controller;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.audit.service.ProfileSafetyCheckService;
import cn.jualn.miniapp.module.user.bo.EffectiveProfileBO;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC routing/binding/representation; authorization itself is tested at the reusable service boundary. */
class EffectiveProfileMvcTest {
    private final UserService service = mock(UserService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(service, Mappers.getMapper(UserConverter.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()))).build();
    private EffectiveProfileBO profile() {
        return EffectiveProfileBO.builder().userId(7L).nickname("同学").avatarUrl(null).bio("").platformOperator(false).build();
    }
    @Test void readsReturnExactlyFiveFieldsWithStringIdentityAndNoStore() throws Exception {
        when(service.getEffectiveProfile(any())).thenReturn(profile());
        mvc.perform(get("/v1/users/me/profile")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.userId").value("7")).andExpect(jsonPath("$.avatarUrl").hasJsonPath())
                .andExpect(jsonPath("$.bio").value("")).andExpect(jsonPath("$.isPlatformOperator").value(false));
        mvc.perform(get("/v1/users/7/profile")).andExpect(status().isOk()).andExpect(jsonPath("$.role").doesNotExist());
        verify(service).getEffectiveProfile(null); verify(service).getEffectiveProfile(7L);
    }
    @Test void closedRequestRejectsNullsAndProtectedOrUnknownFields() throws Exception {
        for (String body : new String[]{"{\"nickname\":null}", "{\"bio\":null}", "{\"avatarObjectKey\":null}",
                "{\"nickname\":\"甲\",\"role\":3}", "{\"nickname\":\"甲\",\"isPlatformOperator\":true}",
                "{\"userId\":\"9\"}", "{\"avatarUrl\":\"https://example.com\"}", "{\"status\":1}", "{\"createdAt\":\"now\"}",
                "{\"nickname\":123}", "{\"bio\":false}", "{\"avatarObjectKey\":[]}", "{\"nickname\":{}}"}) {
            mvc.perform(post("/v1/users/me/profile").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("/problems/validation-error"));
        }
        verifyNoInteractions(service);
    }
    @Test void postReturnsFinalRepresentationAndExistingProblems() throws Exception {
        when(service.updateEffectiveProfile(any())).thenReturn(profile());
        mvc.perform(post("/v1/users/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("7"))
                .andExpect(header().string("Cache-Control", "no-store"));
        doThrow(ProfileSafetyCheckService.rejected()).when(service).updateEffectiveProfile(any());
        mvc.perform(post("/v1/users/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value("/problems/profile-content-rejected"));
        doThrow(ProfileSafetyCheckService.unavailable()).when(service).updateEffectiveProfile(any());
        mvc.perform(post("/v1/users/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"x\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.type").value("/problems/profile-safety-check-unavailable"));
    }
    @Test void previousPatchMethodIsNotAnActiveWriteEntry() throws Exception {
        mvc.perform(patch("/v1/users/me/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"candidate\"}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    @Test void authForbiddenAndMissingRemainStandardProblems() throws Exception {
        for (ResultCode code : new ResultCode[]{ResultCode.UNAUTHORIZED, ResultCode.FORBIDDEN, ResultCode.USER_NOT_FOUND}) {
            doThrow(new BusinessException(code)).when(service).getEffectiveProfile(any());
            mvc.perform(get("/v1/users/me/profile")).andExpect(status().is(code == ResultCode.UNAUTHORIZED ? 401 : code == ResultCode.FORBIDDEN ? 403 : 404));
        }
        mvc.perform(get("/v1/users/not-an-existing-id/profile")).andExpect(status().isNotFound());
    }
}
