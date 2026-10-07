package cn.jualn.miniapp.common.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XssHttpServletRequestWrapperTest {
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
