package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.module.wx.handler.WxEventHandler;
import cn.jualn.miniapp.module.wx.service.impl.WxEventServiceImpl;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxEventServiceImplTest {

    @Mock
    private WxEventHandler subscribeHandler;

    @Mock
    private Map<String, WxEventHandler> handlers;

    @Test
    void handle_shouldRouteSubscribeEventToHandler() {
        XmlMapper xmlMapper = new XmlMapper();
        WxEventServiceImpl wxEventServiceImpl = new WxEventServiceImpl(xmlMapper, handlers);
        when(handlers.get("event:subscribe")).thenReturn(subscribeHandler);

        String xml = """
                <xml>
                  <ToUserName><![CDATA[to]]></ToUserName>
                  <FromUserName><![CDATA[from]]></FromUserName>
                  <CreateTime>123</CreateTime>
                  <MsgType><![CDATA[event]]></MsgType>
                  <Event><![CDATA[subscribe]]></Event>
                  <EventKey><![CDATA[qrscene_bind_10_a1b2c3d4]]></EventKey>
                </xml>
                """;

        String result = wxEventServiceImpl.handle(xml);

        assertEquals("success", result);
        ArgumentCaptor<WxEventMessage> eventCaptor = ArgumentCaptor.forClass(WxEventMessage.class);
        verify(subscribeHandler).handle(eventCaptor.capture());
        WxEventMessage captured = eventCaptor.getValue();
        assertEquals("from", captured.getFromUserName());
        assertEquals("event", captured.getMsgType());
        assertEquals("subscribe", captured.getEvent());
        assertEquals("qrscene_bind_10_a1b2c3d4", captured.getEventKey());
    }

    @Test
    void handle_shouldReturnSuccessWhenXmlParseFails() {
        WxEventServiceImpl wxEventServiceImpl = new WxEventServiceImpl(new XmlMapper(), handlers);

        String result = wxEventServiceImpl.handle("<xml><MsgType>");

        assertEquals("success", result);
        verifyNoInteractions(handlers);
    }
}
