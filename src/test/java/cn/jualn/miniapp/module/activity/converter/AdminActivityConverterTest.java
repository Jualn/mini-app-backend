package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.activity.bo.AdminActivitySaveBO;
import cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminActivityConverterTest {

    private final AdminActivityConverter converter = new AdminActivityConverter();

    @Test
    void toSaveBO_shouldKeepObjectKeyAndIgnoreClientUrlForCosAttachment() {
        AdminActivitySaveRequest request = validRequest();
        AdminActivitySaveRequest.AttachmentItem attachment = new AdminActivitySaveRequest.AttachmentItem();
        attachment.setTypeCode("image");
        attachment.setObjectKey("activity/9/cover.jpg");
        attachment.setUrl("https://untrusted.example/cover.jpg");
        attachment.setName("cover.jpg");
        request.setAttachments(List.of(attachment));

        AdminActivitySaveBO result = converter.toSaveBO(request, null, 9L);

        assertEquals(MediaType.IMAGE, result.getAttachmentItems().get(0).getType());
        assertEquals("activity/9/cover.jpg", result.getAttachmentItems().get(0).getObjectKey());
        assertNull(result.getAttachmentItems().get(0).getUrl());
    }

    @Test
    void toSaveBO_shouldRequireObjectKeyForCosAttachment() {
        AdminActivitySaveRequest request = validRequest();
        AdminActivitySaveRequest.AttachmentItem attachment = new AdminActivitySaveRequest.AttachmentItem();
        attachment.setTypeCode("pdf");
        attachment.setUrl("https://example.com/file.pdf");
        request.setAttachments(List.of(attachment));

        assertThrows(BusinessException.class, () -> converter.toSaveBO(request, null, 9L));
    }

    @Test
    void toSaveBO_shouldAllowHttpsExternalLinkWithoutObjectKey() {
        AdminActivitySaveRequest request = validRequest();
        AdminActivitySaveRequest.AttachmentItem attachment = new AdminActivitySaveRequest.AttachmentItem();
        attachment.setTypeCode("link");
        attachment.setUrl("https://example.com/activity");
        request.setAttachments(List.of(attachment));

        AdminActivitySaveBO result = converter.toSaveBO(request, null, 9L);

        assertEquals(MediaType.URL, result.getAttachmentItems().get(0).getType());
        assertNull(result.getAttachmentItems().get(0).getObjectKey());
        assertEquals("https://example.com/activity", result.getAttachmentItems().get(0).getUrl());
    }

    private AdminActivitySaveRequest validRequest() {
        AdminActivitySaveRequest request = new AdminActivitySaveRequest();
        request.setTitle("活动");
        request.setContent("活动详情");
        request.setLocation("礼堂");
        request.setCategory("culture");
        request.setOrganizer("学生会");
        request.setAudienceCodes(List.of("college"));
        request.setStartTime(LocalDateTime.of(2026, 9, 1, 9, 0));
        request.setEndTime(LocalDateTime.of(2026, 9, 1, 11, 0));
        return request;
    }
}
