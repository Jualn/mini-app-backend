package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import cn.jualn.miniapp.module.wx.dto.WxaMediaCheckMessage;
import cn.jualn.miniapp.module.wx.handler.WxEventHandler;
import cn.jualn.miniapp.module.wx.service.impl.WxEventServiceImpl;
import cn.jualn.miniapp.third.wx.config.WxAccountType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WxEventServiceImplTest {

    @Test
    void handle_routesByAccountAndEvent() {
        WxEventHandler subscribeHandler = mock(WxEventHandler.class);
        WxEventServiceImpl service = new WxEventServiceImpl(
                new XmlMapper(), Map.of("mp:event:subscribe", subscribeHandler));

        assertEquals("success", service.handle(WxAccountType.MP, subscribeXml()));

        ArgumentCaptor<WxBaseMessage> captor = ArgumentCaptor.forClass(WxBaseMessage.class);
        verify(subscribeHandler).handle(captor.capture());
        assertEquals("from", captor.getValue().getFromUserName());
        assertEquals("qrscene_bind_10_a1b2c3d4", captor.getValue().getEventKey());
    }

    @Test
    void handle_parsesMediaCallbackOnceIntoTrustedSubtype() {
        WxEventHandler mediaHandler = mock(WxEventHandler.class);
        WxEventServiceImpl service = new WxEventServiceImpl(
                new XmlMapper(), Map.of("ma:event:wxa_media_check", mediaHandler));
        String xml = """
                <xml><MsgType>event</MsgType><Event>wxa_media_check</Event>
                <appid>ma-app</appid><trace_id>trace-1</trace_id><version>2</version></xml>
                """;

        assertEquals("success", service.handle(WxAccountType.MA, xml));

        ArgumentCaptor<WxBaseMessage> captor = ArgumentCaptor.forClass(WxBaseMessage.class);
        verify(mediaHandler).handle(captor.capture());
        WxaMediaCheckMessage message = assertInstanceOf(WxaMediaCheckMessage.class, captor.getValue());
        assertEquals("trace-1", message.getTraceId());
    }

    @Test
    void handle_rejectsMalformedXmlInsteadOfAcknowledgingIt() {
        WxEventServiceImpl service = new WxEventServiceImpl(new XmlMapper(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.handle(WxAccountType.MP, "<xml><MsgType>"));
    }

    @Test
    void handle_propagatesHandlerFailureInsteadOfAcknowledgingIt() {
        WxEventHandler subscribeHandler = mock(WxEventHandler.class);
        doThrow(new IllegalStateException("temporary failure")).when(subscribeHandler).handle(any());
        WxEventServiceImpl service = new WxEventServiceImpl(
                new XmlMapper(), Map.of("mp:event:subscribe", subscribeHandler));

        assertThrows(IllegalStateException.class,
                () -> service.handle(WxAccountType.MP, subscribeXml()));
    }

    private String subscribeXml() {
        return """
                <xml>
                  <ToUserName>to</ToUserName><FromUserName>from</FromUserName>
                  <CreateTime>123</CreateTime><MsgType>event</MsgType>
                  <Event>subscribe</Event><EventKey>qrscene_bind_10_a1b2c3d4</EventKey>
                </xml>
                """;
    }
}
