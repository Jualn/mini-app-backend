package cn.jualn.miniapp.security;

import cn.jualn.miniapp.web.RequestPeer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import static org.junit.jupiter.api.Assertions.*;

class RequestPeerTest {
    @Test void forwardedHeaderCannotChooseAuthenticationRateBucket() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Forwarded", "for=198.51.100.20;proto=https;host=synthetic.example");
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertEquals("198.51.100.20", wrapped.getRemoteAddr());
            assertEquals("127.0.0.1", RequestPeer.address(wrapped));
        });
    }
}
