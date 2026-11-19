package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.post.controller.GrowthRecordController;
import cn.jualn.miniapp.module.post.service.*;
import cn.jualn.miniapp.module.post.bo.GrowthRecordBO;
import cn.jualn.miniapp.module.media.bo.GrowthMediaBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GrowthRecordMvcTest {
    GrowthRecordService records=mock(GrowthRecordService.class);
    MockMvc mvc(){return MockMvcBuilders.standaloneSetup(new GrowthRecordController(records)).setControllerAdvice(new cn.jualn.miniapp.common.exception.GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()))).build();}
    @Test void createsUnwrappedRepresentationWithStringIdsNumericCountsAndNoStore() throws Exception {
        var at=OffsetDateTime.parse("2026-11-03T12:00:00+08:00");var value=new GrowthRecordBO.Record(new GrowthRecordBO.Link(123,"person","branch","GROWTH"),UserSimpleBO.builder().id(1L).nickname("author").build(),null,"content",List.of(),at,null,List.of(),new GrowthRecordBO.Lifecycle(at,"123:0",null,"PRIVATE","CHECKING",false,false,null),List.of("EDIT","DELETE"),0,0,0,0);
        when(records.create(any(),any())).thenReturn(value);
        mvc().perform(post("/v1/growth-records").header("Idempotency-Key","growth-key-123456").contentType("application/json").content("{\"organizationId\":\"branch\",\"recordType\":\"GROWTH\",\"content\":\"content\",\"mediaIds\":[]}"))
            .andExpect(status().isCreated()).andExpect(header().string("Location","/v1/growth-records/123")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Vary","Authorization"))
            .andExpect(jsonPath("$.link.postId").value("123")).andExpect(jsonPath("$.likeCount").isNumber()).andExpect(jsonPath("$.lifecycle.effectiveVersion").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.code").doesNotExist());
    }
    @Test void noBindingContextOmitsOptionalPersonAndMediaRegistrationIsCreated() throws Exception {
        when(records.context()).thenReturn(new GrowthQualification.Context(null,OffsetDateTime.now(),List.of()));
        mvc().perform(get("/v1/users/me/growth-publishing-context")).andExpect(status().isOk()).andExpect(jsonPath("$.personId").doesNotExist()).andExpect(jsonPath("$.organizations").isEmpty());
        when(records.register(anyString(),anyInt())).thenReturn(new GrowthMediaBO.File(22,"IMAGE","image.png","image/png",100,"CHECKING","https://api.example.test/v1/media/files/22/content",OffsetDateTime.now().plusHours(24)));
        mvc().perform(post("/v1/growth-media").contentType("application/json").content("{\"uploadId\":\"upload\",\"fileIndex\":0}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.mediaId").value("22")).andExpect(jsonPath("$.sizeBytes").isNumber());
        var credentials=new GrowthMediaBO.Credentials("private","region",List.of("private/growth-upload/key"),"id","secret","token",1900000000L,null);
        when(records.upload(any())).thenReturn(new GrowthMediaBO.Upload("upload",OffsetDateTime.now().plusMinutes(10),OffsetDateTime.now().plusHours(24),credentials));
        mvc().perform(post("/v1/growth-media/uploads").contentType("application/json").content("{\"organizationId\":\"branch\",\"recordType\":\"GROWTH\",\"files\":[{\"kind\":\"IMAGE\",\"originalName\":\"image.png\",\"contentType\":\"image/png\",\"sizeBytes\":100}]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.credentials.expiredTime").isNumber());
    }
    @Test void unknownCreateFieldsAreRejectedBeforeServiceInvocation() throws Exception {
        mvc().perform(post("/v1/growth-records").header("Idempotency-Key","growth-key-123456").contentType("application/json").content("{\"organizationId\":\"branch\",\"recordType\":\"GROWTH\",\"personId\":\"forged\",\"content\":\"content\",\"mediaIds\":[]}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(records);
    }
}
