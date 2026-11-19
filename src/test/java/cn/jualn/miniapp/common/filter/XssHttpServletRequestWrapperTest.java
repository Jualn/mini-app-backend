package cn.jualn.miniapp.common.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class XssHttpServletRequestWrapperTest {
    @Test
    void preservesMergePatchJsonBody() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("PATCH");
        request.setContentType("application/merge-patch+json;charset=UTF-8");
        byte[] body = """
                {"reason":"测试","verificationReference":"测试","majorClass":"人工"}
                """.getBytes(StandardCharsets.UTF_8);
        request.setContent(body);

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request);

        assertArrayEquals(body, wrapper.getInputStream().readAllBytes());
    }

    @Test
    void preservesProtocolHeaderValues() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.IF_MATCH, "\"activity-3-v1\"");

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request);

        assertEquals("\"activity-3-v1\"", wrapper.getHeader(HttpHeaders.IF_MATCH));
        assertEquals(Collections.singletonList("\"activity-3-v1\""),
                Collections.list(wrapper.getHeaders(HttpHeaders.IF_MATCH)));
    }

    @Test
    void continuesEscapingRequestParameters() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("keyword", "<script>alert(1)</script>");

        XssHttpServletRequestWrapper wrapper = new XssHttpServletRequestWrapper(request);

        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", wrapper.getParameter("keyword"));
    }
}
