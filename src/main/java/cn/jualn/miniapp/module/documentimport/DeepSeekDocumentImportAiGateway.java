package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.third.ai.client.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeepSeekDocumentImportAiGateway implements DocumentImportAiGateway {
    private final DeepSeekClient client;

    @Override
    public String generate(String prompt) {
        return client.generate(prompt);
    }
}
