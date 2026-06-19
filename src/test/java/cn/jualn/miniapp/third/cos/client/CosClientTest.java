package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.dto.CosTemporaryCredentialDTO;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class CosClientTest {

    @Mock
    private COSClient cosSdkClient;

    @Test
    void parseTemporaryCredential_shouldMapResponseFields() throws Exception {
        CosClient cosClient = new CosClient(cosSdkClient, new CosProperties(
                "secret-id",
                "secret-key",
                "mini-bucket",
                "ap-guangzhou",
                "https://example.com/public",
                900L,
                "https://example.com"
        ));

        long expiredTime = LocalDateTime.of(2026, 5, 12, 12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        String responseBody = """
                {
                  "Response": {
                    "Credentials": {
                      "TmpSecretId": "tmp-secret-id",
                      "TmpSecretKey": "tmp-secret-key",
                      "Token": "session-token",
                      "ExpiredTime": %d,
                      "Expiration": "2026-05-12T12:00:00Z"
                    }
                  }
                }
                """.formatted(expiredTime);

        CosTemporaryCredentialDTO dto = CosClientTestUtils.parseTemporaryCredential(responseBody);

        assertEquals("tmp-secret-id", dto.getTmpSecretId());
        assertEquals("tmp-secret-key", dto.getTmpSecretKey());
        assertEquals("session-token", dto.getSessionToken());
        assertEquals(expiredTime, dto.getExpiredTime());
        assertNotNull(dto.getExpireAt());
    }

    @Test
    void buildCanonicalRequest_shouldContainSignedHeaders() throws Exception {
        CosClient cosClient = new CosClient(cosSdkClient, new CosProperties(
                "secret-id",
                "secret-key",
                "mini-bucket",
                "ap-guangzhou",
                "https://example.com/public",
                900L,
                "https://example.com"
        ));

        String canonicalRequest = CosClientTestUtils.buildCanonicalRequest("{}");

        assertTrue(canonicalRequest.startsWith("POST\n/\n\n"));
        assertTrue(canonicalRequest.contains("content-type:application/json; charset=utf-8"));
        assertTrue(canonicalRequest.contains("host:sts.tencentcloudapi.com"));
        assertTrue(canonicalRequest.contains("content-type;host"));
    }
}
