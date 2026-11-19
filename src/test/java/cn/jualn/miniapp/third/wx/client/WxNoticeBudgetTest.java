package cn.jualn.miniapp.third.wx.client;

import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.third.wx.config.WxProperties;
import cn.jualn.miniapp.third.wx.dto.MpSubscribeMessageRequest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All requests are rewritten to a loopback synthetic provider. */
class WxNoticeBudgetTest {
    HttpServer server; WxClient client; RedisService redis; SimpleMeterRegistry meters;
    AtomicInteger sends=new AtomicInteger(),tokens=new AtomicInteger();
    volatile boolean expired; volatile int delay; volatile String reply="{\"errcode\":0,\"msgid\":19}";
    volatile String sendPath, sendBody;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{
            String body;
            if(x.getRequestURI().getPath().endsWith("/token")) {tokens.incrementAndGet();body="{\"access_token\":\"fixture-token\",\"expires_in\":7200}";}
            else {sendPath=x.getRequestURI().getPath();sendBody=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);int n=sends.incrementAndGet();body=expired&&n==1?"{\"errcode\":40001}":reply;}
            try {Thread.sleep(delay);} catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");
            x.sendResponseHeaders(200,bytes.length);try{x.getResponseBody().write(bytes);}finally{x.close();}
        });server.start();
        var web=WebClient.builder().filter((r,next)->next.exchange(ClientRequest.from(r).url(URI.create(
                "http://127.0.0.1:"+server.getAddress().getPort()+r.url().getRawPath()+"?"+r.url().getRawQuery())).build())).build();
        redis=mock(RedisService.class);meters=new SimpleMeterRegistry();
        client=new WxClient(web,new WxProperties(new WxProperties.Mp("fixture-mp","fixture-secret",null,null,null),
                new WxProperties.Ma("fixture-ma","fixture-secret",null,null)),redis,meters);
    }
    @AfterEach void cleanup(){server.stop(0);meters.close();}
    MpSubscribeMessageRequest request(){return MpSubscribeMessageRequest.builder().toUser("fixture-user").templateId("fixture-template").build();}
    @Test void expiredTokenRefreshesOnceAndKeepsBizsendProduct() {
        expired=true;assertEquals(0,client.sendMpSubscribeMessage(request()).getErrcode());
        assertEquals(2,tokens.get());assertEquals(2,sends.get());
    }
    @Test void miniUsesItsOwnTokenEndpointAndWireRepresentation() throws Exception {
        expired=true;
        var request=new cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest("mini-user","mini-template",
                "pages/message/message?notificationId=7","trial",java.util.Map.of("thing1",
                new cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest.DataItem("fixture")));
        assertEquals(0,client.sendMiniSubscribeMessage(request).getErrcode());
        assertEquals(2,sends.get());assertEquals(2,tokens.get());
        assertEquals("/cgi-bin/message/subscribe/send",sendPath);
        var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(sendBody);
        assertEquals("mini-user",body.get("touser").asText());
        assertEquals("trial",body.get("miniprogram_state").asText());
        assertFalse(body.has("miniprogram"));assertFalse(body.has("pagepath"));
        verify(redis,never()).getString(cn.jualn.miniapp.common.constant.RedisKeyConstant.wxAccessToken("mp","fixture-mp"));
    }
    @Test void miniIncompleteResponseIsUnknownAndCannotTriggerAnotherSend() {
        reply="{}";
        var request=new cn.jualn.miniapp.third.wx.dto.MiniSubscribeMessageRequest("mini-user","mini-template",
                "pages/message/message","formal",java.util.Map.of());
        var error=assertThrows(ExternalServiceException.class,()->client.sendMiniSubscribeMessage(request));
        assertNull(error.getProviderCode());assertNull(error.getCause());assertEquals(1,sends.get());
    }
    @Test void authoritativeProviderErrorNeverExposesEchoedIdentityOrSecret() {
        reply="{\"errcode\":40003,\"errmsg\":\"fixture-secret fixture-user access_token=fixture-token\"}";
        var e=assertThrows(ExternalServiceException.class,()->client.sendMpSubscribeMessage(request()));
        assertEquals("40003",e.getProviderCode());assertFalse(e.getMessage().contains("fixture-"));assertNull(e.getCause());
    }
    @Test void tokenAndSendShareOneBudgetAndUncertainTimeoutHasNoRetry() {
        delay=160;long started=System.nanoTime();
        var e=assertThrows(ExternalServiceException.class,()->client.sendMpSubscribeMessage(request(),Duration.ofMillis(250)));
        assertNull(e.getProviderCode());assertNull(e.getCause());
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<1000);assertTrue(sends.get()<=1);
    }
    @Test void blockedCacheCannotExceedAttemptBudgetOrStartProviderAfterExpiry() throws Exception {
        when(redis.getString(anyString())).thenAnswer(i->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "fixture-token";});
        long started=System.nanoTime();
        assertThrows(ExternalServiceException.class,()->client.sendMpSubscribeMessage(request(),Duration.ofMillis(100)));
        assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<700);
        Thread.sleep(120);assertEquals(0,sends.get());
    }
}
