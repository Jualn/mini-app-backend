package cn.jualn.miniapp.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;

/**
 * Socket peer for authentication abuse budgets. Framework Forwarded wrappers must not let
 * caller-supplied Forwarded/X-Forwarded-For select a different rate bucket. A reverse proxy
 * therefore shares a backend budget; any per-client edge limits belong to the trusted edge.
 */
public final class RequestPeer {
    private RequestPeer() {
    }

    public static String address(ServletRequest request) {
        while (request instanceof ServletRequestWrapper wrapper) request = wrapper.getRequest();
        return request.getRemoteAddr();
    }
}
