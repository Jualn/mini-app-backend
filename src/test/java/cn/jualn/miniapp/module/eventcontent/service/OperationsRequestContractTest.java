package cn.jualn.miniapp.module.eventcontent.service;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.module.exam.vo.admin.AdminPublicEventDetailVO;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.config.JacksonConfig;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OperationsRequestContractTest {
    @Test void configuredJsonPreservesBigIdsTimeAndMediaEnum() throws Exception {
        var mapper=new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
        var request=mapper.readValue("""
            {"title":"测试","category":0,"eventType":2,"audienceScope":1,"registrationMode":1,
             "participantMode":0,"startPrecision":1,"endPrecision":0,"registrationStartPrecision":0,
             "registrationEndPrecision":0,"startTime":"2026-09-08T00:00",
             "coverAttachmentId":"9007199254740993","attachments":[{"type":"IMAGE","objectKey":"exam/test.png"}]}
            """,AdminPublicEventSaveRequest.class);
        assertEquals(9007199254740993L,request.getCoverAttachmentId());
        assertEquals(cn.jualn.miniapp.common.enums.MediaType.IMAGE,request.getAttachments().get(0).getType());
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        var response=new AdminPublicEventDetailVO();response.setId(9007199254740993L);
        assertEquals("9007199254740993",mapper.readTree(mapper.writeValueAsString(response)).get("id").textValue());
    }
    @Test void publicEventPermissionsBelongToOperationsRoles() {
        var policy=new AdminPermissionPolicy();
        assertTrue(policy.permissions(UserRole.OPR).contains(AdminPermissionPolicy.PUBLIC_EVENT_EDIT));
        assertTrue(policy.permissions(UserRole.USER).isEmpty());
    }
}
