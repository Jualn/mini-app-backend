package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real YAML/placeholder binding; no remote COS calls or permissive safety substitute. */
class PointMaterialConfigurationTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(context -> {
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }).withUserConfiguration(Config.class);
    }
    private void dependency(ApplicationContextRunner runner, String expected) {
        runner.run(context -> {
            var provider = context.getBean(CosPointMaterialProvider.class);
            var failure = assertThrows(ContractProblemException.class, () -> provider.credentials("synthetic",
                    List.of("points/upload/synthetic"), LocalDateTime.now().plusMinutes(10)));
            assertEquals(expected, failure.getMessage());
            verifyNoInteractions(context.getBean(COSClient.class));
        });
    }
    @Test void missingPrivateBucketDoesNotFallBackToPublicStorage() {
        dependency(context().withPropertyValues("COS_PRIVATE_BUCKET="), "PRIVATE_MEDIA");
    }
    @Test void dedicatedEnvironmentVariableBindsButDoesNotBypassMissingSafety() {
        dependency(context().withPropertyValues("COS_PRIVATE_BUCKET=campus-test-private-1250000000"), "CONTENT_SAFETY");
    }
    @Test void publicBucketCannotBeConfiguredAsPrivateMaterialStorage() {
        dependency(context().withPropertyValues("COS_PRIVATE_BUCKET=campus-test-public-1250000000"), "PRIVATE_MEDIA");
    }
    @Test void localVideoProbeIsConfiguredSeparatelyFromOptionalCloudModeration() {
        context().withPropertyValues("ARTICLE_FFPROBE_PATH=D:/tools/ffprobe.exe", "COS_VIDEO_AUDIT_ENABLED=false").run(context -> {
            assertEquals("D:/tools/ffprobe.exe", context.getEnvironment().getProperty("app.article.ffprobe-path"));
            assertEquals("false", context.getEnvironment().getProperty("cos.video-audit.enabled"));
            verifyNoInteractions(context.getBean(COSClient.class));
        });
    }
    @Configuration
    @Import(CosPointMaterialProvider.class)
    static class Config {
        @Bean COSClient cos() { return mock(COSClient.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean CosProperties properties() {
            var properties = mock(CosProperties.class);
            when(properties.getBucket()).thenReturn("campus-test-public-1250000000");
            return properties;
        }
    }
}
