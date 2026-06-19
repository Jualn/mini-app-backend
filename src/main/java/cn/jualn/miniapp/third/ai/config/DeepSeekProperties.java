package cn.jualn.miniapp.third.ai.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "deep-seek")
public class DeepSeekProperties {

    private final String apiKey;

}
