package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult.Decision;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosVideoAuditClientTest {
    byte[] response(String state,String extra) {
        return ("<Response><JobsDetail><JobId>v123</JobId><DataId>article-video-audit:7</DataId>"
                +"<Object>article/retained/9/video</Object><State>"+state+"</State>"+extra+"</JobsDetail></Response>").getBytes(StandardCharsets.UTF_8);
    }
    @Test void onlyCompleteSuccessWithExplicitNormalResultsPasses() {
        assertEquals(Decision.PENDING,CosVideoAuditClient.parse(response("Auditing","")).decision());
        assertEquals(Decision.FAILED,CosVideoAuditClient.parse(response("Failed","")).decision());
        String good="<Result>0</Result><Snapshot><Result>0</Result></Snapshot><AudioSection><Result>0</Result></AudioSection>";
        assertEquals(Decision.PASS,CosVideoAuditClient.parse(response("Success",good)).decision());
        assertEquals(Decision.REJECT,CosVideoAuditClient.parse(response("Success",good.replace("<AudioSection><Result>0","<AudioSection><Result>1"))).decision());
        assertEquals(Decision.REVIEW,CosVideoAuditClient.parse(response("Success",good.replace("<AudioSection><Result>0","<AudioSection><Result>2"))).decision());
        for(String bad:new String[]{"", "<Result>0</Result>","<Result>unknown</Result><Snapshot><Result>0</Result></Snapshot>",
                "<Result>0</Result><Snapshot/>",good+"<Result>0</Result>",good+"<Code>InternalError</Code>"})
            assertThrows(ExternalServiceException.class,()->CosVideoAuditClient.parse(response("Success",bad)));
    }
    @Test void xmlEntitiesAndNonProviderStructuresAreRejected() {
        for(String bad:new String[]{"<!DOCTYPE Response [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><Response>&x;</Response>",
                "<Error><Code>Denied</Code></Error>","<Response><JobsDetail/><JobsDetail/></Response>","<Response>"})
            assertThrows(ExternalServiceException.class,()->CosVideoAuditClient.parse(bad.getBytes(StandardCharsets.UTF_8)));
        assertThrows(ExternalServiceException.class,()->CosVideoAuditClient.parse(new byte[2*1024*1024+1]));
    }
    @Test void submitsManagedObjectWithAudioAndExplicitPolicyAndNoCallbackOrFreeze() {
        var captured=new AtomicReference<String>(); var path=new AtomicReference<String>();
        var client=new CosVideoAuditClient(null) {
            @Override protected byte[] exchange(String method,String bucket,String region,String resource,String xml) {
                assertEquals("POST",method); assertEquals("bucket-1250000000",bucket); assertEquals("ap-guangzhou",region);
                captured.set(xml); path.set(resource); return response("Submitted","");
            }
        };
        client.submit("bucket-1250000000","ap-guangzhou","article/retained/9/a&b","article-video-audit:7","strategy",1);
        assertEquals("/video/auditing",path.get());
        assertTrue(captured.get().contains("<Object>article/retained/9/a&amp;b</Object>"));
        assertTrue(captured.get().contains("<DetectContent>1</DetectContent>"));
        assertTrue(captured.get().contains("<BizType>strategy</BizType>"));
        assertTrue(captured.get().contains("<Count>10000</Count>"));
        assertFalse(captured.get().contains("<Callback>")); assertFalse(captured.get().contains("<Freeze>"));
        assertThrows(ExternalServiceException.class,()->client.query("bucket-1250000000","ap-guangzhou","../bad"));
    }
    @Test void signedTransportRunsAgainstLoopbackAndNeverFollowsRedirectOrRetriesSubmission() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger(); var authorization=new AtomicReference<String>();
        var digest=new AtomicReference<String>(); var requestBody=new AtomicReference<byte[]>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/video/auditing",exchange->{
            requests.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); digest.set(exchange.getRequestHeaders().getFirst("Content-MD5"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            var response=response("Submitted",""); exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            var cos=new CosProperties("synthetic-id","synthetic-secret","bucket-1250000000","ap-guangzhou","https://cdn.example",1800,"cdn.example");
            var client=new CosVideoAuditClient(cos) {
                @Override protected java.net.HttpURLConnection openConnection(java.net.URI uri) throws java.io.IOException {
                    assertEquals("https",uri.getScheme()); assertEquals("bucket-1250000000.ci.ap-guangzhou.myqcloud.com",uri.getHost());
                    return (java.net.HttpURLConnection)java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+uri.getPath()).toURL().openConnection();
                }
            };
            assertEquals(Decision.PENDING,client.submit(cos.getBucket(),cos.getRegion(),"article/retained/9/video","article-video-audit:7","strategy",1).decision());
            assertTrue(authorization.get().contains("q-sign-algorithm=sha1")); assertTrue(authorization.get().contains("content-md5"));
            assertEquals(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(requestBody.get())),digest.get());
            server.removeContext("/video/auditing"); server.createContext("/video/auditing",exchange->{
                requests.incrementAndGet(); exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unexpected");
                exchange.sendResponseHeaders(307,-1); exchange.close();
            });
            assertThrows(ExternalServiceException.class,()->client.submit(cos.getBucket(),cos.getRegion(),"article/retained/9/video","article-video-audit:7","strategy",1));
            assertEquals(2,requests.get());
        } finally { server.stop(0); }
    }
}
