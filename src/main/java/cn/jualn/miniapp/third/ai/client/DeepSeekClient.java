package cn.jualn.miniapp.third.ai.client;

import cn.jualn.miniapp.third.ai.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient {
    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DeepSeekClient(DeepSeekProperties properties,
                          @Qualifier("objectMapper") ObjectMapper objectMapper,
                          @Qualifier("aiWebClient") WebClient webClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
    }

    /** Collect one provider stream; callers validate the complete document before exposing it. */
    public String generate(String prompt) {
        Map<String, Object> body = Map.of(
                "model", "deepseek-v4-flash",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", true);
        String value = webClient.post()
                .uri("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !"[DONE]".equals(line))
                .map(this::content)
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .block();
        return value == null ? "" : value;
    }

    private String content(String line) {
        try {
            JsonNode node = objectMapper.readTree(line).at("/choices/0/delta/content");
            return node.isMissingNode() || node.isNull() ? "" : node.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("AI provider returned an invalid stream frame", exception);
        }
    }
}
