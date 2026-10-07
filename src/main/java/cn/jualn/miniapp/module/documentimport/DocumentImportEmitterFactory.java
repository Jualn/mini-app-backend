package cn.jualn.miniapp.module.documentimport;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class DocumentImportEmitterFactory {
    public SseEmitter create(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }
}
