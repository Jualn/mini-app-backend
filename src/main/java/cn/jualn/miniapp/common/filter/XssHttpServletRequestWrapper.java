package cn.jualn.miniapp.common.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.web.util.HtmlUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public XssHttpServletRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = sanitizeBody(request);
    }

    @Override
    public String getParameter(String name) {
        return sanitize(super.getParameter(name));
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) {
            return null;
        }
        String[] sanitized = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            sanitized[i] = sanitize(values[i]);
        }
        return sanitized;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> parameterMap = super.getParameterMap();
        Map<String, String[]> sanitized = new HashMap<>(parameterMap.size());
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String[] values = entry.getValue();
            String[] sanitizedValues = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                sanitizedValues[i] = sanitize(values[i]);
            }
            sanitized.put(entry.getKey(), sanitizedValues);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    @Override
    public String getHeader(String name) {
        return sanitize(super.getHeader(name));
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        Enumeration<String> headers = super.getHeaders(name);
        return new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return headers.hasMoreElements();
            }

            @Override
            public String nextElement() {
                return sanitize(headers.nextElement());
            }
        };
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return byteArrayInputStream.read();
            }

            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async read is not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private byte[] sanitizeBody(HttpServletRequest request) throws IOException {
        byte[] original = request.getInputStream().readAllBytes();
        if (original.length == 0) {
            return original;
        }

        // 如果是 JSON 格式，直接返回原始字节，不进行 htmlEscape
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            return original;
        }

        String content = new String(original, StandardCharsets.UTF_8);
        return sanitize(content).getBytes(StandardCharsets.UTF_8);
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return HtmlUtils.htmlEscape(value);
    }
}

