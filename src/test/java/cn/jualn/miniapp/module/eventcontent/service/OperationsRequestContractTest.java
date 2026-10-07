package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.exam.dto.admin.AdminPublicEventSaveRequest;
import cn.jualn.miniapp.common.enums.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsRequestContractTest {
    @Test
    void configuredJsonReadsCanonicalPublicEventDraftAndOpaqueIds() throws Exception {
        var mapper = new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
        var request = mapper.readValue("""
            {"title":"测试公共事项","summary":"摘要","type":"COMPETITION","sourceName":"组委会",
             "sourceUrl":"https://example.org/notice","coverAttachmentId":"9007199254740993",
             "timeline":[],"sections":[],"actions":[],"contacts":[],
             "attachments":[{"attachmentId":"9007199254740993","displayOrder":0}]}
            """, AdminPublicEventSaveRequest.class);
        assertEquals("9007199254740993", request.getCoverAttachmentId());
        assertEquals("9007199254740993", request.getAttachments().get(0).getAttachmentId());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void publicEventPermissionsBelongToOperationsRoles() {
        var policy = new AdminPermissionPolicy();
        assertTrue(policy.permissions(UserRole.OPR).contains(AdminPermissionPolicy.PUBLIC_EVENT_EDIT));
        assertTrue(policy.permissions(UserRole.USER).isEmpty());
    }
}
