package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.third.ai.client.DeepSeekClient;
import cn.jualn.miniapp.third.ai.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DocumentImportBeanWiringTest {

    @Test
    void selectsJsonMapperWhenXmlMapperIsAlsoRegistered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("objectMapper", ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean("xmlMapper", XmlMapper.class, () -> new XmlMapper());
            context.registerBean(DeepSeekProperties.class, () -> new DeepSeekProperties("test-key"));
            context.registerBean("aiWebClient", WebClient.class, () -> WebClient.builder().build());
            context.registerBean(DeepSeekClient.class);

            context.registerBean(DocumentImportFileReader.class,
                    () -> mock(DocumentImportFileReader.class));
            context.registerBean(DocumentImportPromptBuilder.class,
                    () -> mock(DocumentImportPromptBuilder.class));
            context.registerBean(DocumentImportAiGateway.class,
                    () -> mock(DocumentImportAiGateway.class));
            context.registerBean(DocumentImportSuggestionValidator.class);
            context.registerBean(DocumentImportEmitterFactory.class,
                    () -> mock(DocumentImportEmitterFactory.class));
            context.registerBean(DocumentImportProperties.class, DocumentImportProperties::new);
            context.registerBean("aiTaskExecutor", AsyncTaskExecutor.class,
                    () -> new TaskExecutorAdapter(Runnable::run));
            context.registerBean(DocumentImportService.class);

            context.refresh();

            assertNotNull(context.getBean(DocumentImportSuggestionValidator.class));
            assertNotNull(context.getBean(DocumentImportService.class));
            assertNotNull(context.getBean(DeepSeekClient.class));
        }
    }
}
