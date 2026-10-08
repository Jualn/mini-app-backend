package cn.jualn.miniapp.third.cos.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.third.cos.client.CosClient;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CosAttachmentUrlTest {
    private final CosService service = new CosService(mock(CosClient.class),
            new CosProperties("unused", "unused", "campus-123", "ap-test",
                    "https://public.example/media", 600, "cdn.example"), new ObjectMapper());

    @Test void acceptsConfiguredBasesAndNativeBucketWithExactlyOneDecode() {
        assertEquals("activity/1/文件.pdf", service.resolveManagedObjectKey("https://cdn.example/activity/1/%E6%96%87%E4%BB%B6.pdf"));
        assertEquals("exam/1/a.pdf", service.resolveManagedObjectKey("https://public.example/media/exam/1/a.pdf"));
        assertEquals("activity/1/a.pdf", service.resolveManagedObjectKey("https://campus-123.cos.ap-test.myqcloud.com/activity/1/a.pdf"));
        assertTrue(service.managedPublicUrls("activity/1/文件.pdf").contains("https://cdn.example/activity/1/%E6%96%87%E4%BB%B6.pdf"));
    }

    @Test void externalHostIsNotBound() {
        assertNull(service.resolveManagedObjectKey("https://outside.example/activity/1/a.pdf"));
        assertNull(service.resolveManagedObjectKey("https://cdn.example.evil.example/activity/1/a.pdf"));
    }

    @Test void rejectsManagedAmbiguityTraversalAndWrongBase() {
        for (String url : java.util.List.of("https://cdn.example/activity/1/%2Fa.pdf",
                "https://cdn.example/activity/1/%252e%252e/a.pdf", "https://cdn.example/activity/1/../a.pdf",
                "https://cdn.example/activity/1/a.pdf?key=x", "https://user@cdn.example/activity/1/a.pdf",
                "https://cdn.example:444/activity/1/a.pdf", "https://public.example/other/a.pdf")) {
            assertThrows(BusinessException.class, () -> service.resolveManagedObjectKey(url), url);
        }
    }
}
