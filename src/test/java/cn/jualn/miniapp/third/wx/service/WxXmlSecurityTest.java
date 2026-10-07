package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.config.WebClientConfig;
import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WxXmlSecurityTest {

    @Test
    void productionXmlMapper_rejectsDoctypeAndExternalEntity() {
        XmlMapper mapper = new WebClientConfig().xmlMapper();
        String malicious = """
                <!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///does-not-exist">]>
                <xml><MsgType>&xxe;</MsgType></xml>
                """;

        assertThrows(Exception.class, () -> mapper.readValue(malicious, WxBaseMessage.class));
    }
}
