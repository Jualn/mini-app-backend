package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult.Decision;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.http.HttpMethodName;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.*;

/**
 * CI REST adapter using the installed COS SDK signer, without upgrading the storage SDK.
 * Fixed HTTPS endpoint, no redirects, no transport retries, bounded bodies and timeouts.
 */
@Component
public class CosVideoAuditClient {
    private final CosProperties cos;

    public CosVideoAuditClient(CosProperties cos) {
        this.cos = cos;
    }

    public CosVideoAuditResult submit(String bucket, String region, String objectKey, String dataId,
                                      String bizType, int intervalSeconds) {
        String xml = "<Request><Input><Object>" + escape(objectKey) + "</Object><DataId>" + escape(dataId)
                + "</DataId></Input><Conf><BizType>" + escape(bizType) + "</BizType><DetectContent>1</DetectContent>"
                + "<Snapshot><Mode>Interval</Mode><TimeInterval>" + intervalSeconds
                + "</TimeInterval><Count>10000</Count></Snapshot></Conf></Request>";
        return parse(exchange("POST", bucket, region, "/video/auditing", xml));
    }

    public CosVideoAuditResult query(String bucket, String region, String jobId) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable(null);
        return parse(exchange("GET", bucket, region, "/video/auditing/" + jobId, null));
    }

    protected byte[] exchange(String method, String bucket, String region, String path, String xml) {
        if (bucket == null || !bucket.matches("[a-z0-9-]+-[0-9]+") || region == null || !region.matches("[a-z]+-[a-z]+(?:-[a-z]+)?[0-9]*"))
            throw unavailable(null);
        HttpURLConnection connection = null;
        try {
            String host = bucket + ".ci." + region + ".myqcloud.com";
            connection = openConnection(URI.create("https://" + host + path));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(10000);
            Map<String, String> headers = new HashMap<>();
            headers.put("host", host);
            headers.put("content-type", "application/xml; charset=UTF-8");
            byte[] body = xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8);
            // Sign the body digest too; proxies cannot alter the policy/object while retaining authorization.
            if (xml != null)
                headers.put("content-md5", Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(body)));
            // Host is supplied by the fixed URL.
            for (var header : headers.entrySet())
                if (!"host".equals(header.getKey())) connection.setRequestProperty(header.getKey(), header.getValue());
            connection.setRequestProperty("Authorization", new COSSigner().buildAuthorizationStr(HttpMethodName.valueOf(method), path,
                    headers, Map.of(), new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey()), new Date(System.currentTimeMillis() + 60000)));
            if (xml != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw unavailable(null); // Even a failed POST response is never retried here.
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                long deadline = System.nanoTime() + 15_000_000_000L;
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024 || System.nanoTime() > deadline)
                        throw unavailable(null);
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (ExternalServiceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable(failure);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    protected HttpURLConnection openConnection(URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    static CosVideoAuditResult parse(byte[] xml) {
        try {
            if (xml == null || xml.length > 2 * 1024 * 1024) throw unavailable(null);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            Element response = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if (!"Response".equals(response.getTagName())) throw unavailable(null);
            Element detail = child(response, "JobsDetail");
            String id = value(detail, "JobId"), state = value(detail, "State");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable(null);
            Decision decision;
            if (Set.of("Submitted", "Snapshoting", "Auditing").contains(state == null ? "" : state))
                decision = Decision.PENDING;
            else if ("Failed".equals(state)) decision = Decision.FAILED;
            else if ("Success".equals(state)) {
                if (nonempty(value(detail, "Code"))) throw unavailable(null);
                decision = switch (Objects.toString(value(detail, "Result"), "")) {
                    case "0" -> Decision.PASS;
                    case "1" -> Decision.REJECT;
                    case "2" -> Decision.REVIEW;
                    default -> throw unavailable(null);
                };
                // A contradictory/missing segment result must never turn a top-level zero into approval.
                for (String section : List.of("Snapshot", "AudioSection")) {
                    for (Element segment : children(detail, section)) {
                        String result = value(segment, "Result");
                        if ("1".equals(result)) decision = Decision.REJECT;
                        else if ("2".equals(result) && decision != Decision.REJECT) decision = Decision.REVIEW;
                        else if (!"0".equals(result) && !"2".equals(result)) throw unavailable(null);
                    }
                }
                if (children(detail, "Snapshot").isEmpty()) throw unavailable(null);
            } else throw unavailable(null);
            return new CosVideoAuditResult(id, value(detail, "DataId"), value(detail, "Object"), state, decision);
        } catch (ExternalServiceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isBlank() && !"0".equals(value);
    }

    private static Element child(Element parent, String name) {
        var values = children(parent, name);
        if (values.size() != 1) throw unavailable(null);
        return values.get(0);
    }

    private static String value(Element parent, String name) {
        var values = children(parent, name);
        if (values.isEmpty()) return null;
        if (values.size() != 1 || values.get(0).getChildNodes().getLength() > 1) throw unavailable(null);
        if (values.get(0).getFirstChild() instanceof Element) throw unavailable(null);
        return values.get(0).getTextContent().trim();
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getTagName())) result.add(element);
        return result;
    }

    private static String escape(String value) {
        return Objects.requireNonNull(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static ExternalServiceException unavailable(Throwable cause) {
        return new ExternalServiceException(ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "CI video audit response unavailable", cause);
    }
}
