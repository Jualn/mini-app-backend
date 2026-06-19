package cn.jualn.miniapp.third.ai.client;

import cn.jualn.miniapp.third.ai.config.DeepSeekProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 调用DeepSeek的Chat Completions接口，获取流式响应。每当接收到一个新的文本块时，
     * 调用onChunk.accept(chunk)将这个文本块传递给调用者。
     * 当整个响应完成时，调用onDone.run()通知调用者。
     * 需要注意的是，DeepSeek的流式响应是以文本块的形式发送的，每个文本块都以"data:"开头，
     * 并且最后会有一个特殊的文本块"data: [DONE]"表示响应结束。
     *
     * @param prompt 用户输入的提示文本
     * @param onChunk 每当接收到一个新的文本块时调用，参数是这个文本块的内容
     * @param onDone 当整个响应完成时调用
     * @param onError 当发生错误时调用，参数是异常对象
     */
    public void streamChat(String prompt, Consumer<String> onChunk,
                           Runnable onDone, Consumer<Throwable> onError) {
        Map<String, Object> body = Map.of(
                "model", "deepseek-v4-flash",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", true
        );

        webClient.post()
                .uri("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer " + deepSeekProperties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        line -> {
                            // line 的格式是 "{json}"，已经被去除 data: 前缀了,
                            // 原本的是data: {json}，最后会有一个特殊的行 data: [DONE]
                            if ("[DONE]".equals(line)) return;

                            try {
                                JsonNode contentNode = objectMapper.readTree(line).at("/choices/0/delta/content");

                                if (contentNode.isMissingNode() || contentNode.isNull()) return;

                                String chunk = contentNode.asText();

                                if (!chunk.isEmpty()) onChunk.accept(chunk);

                            } catch (Exception e) {
                                onError.accept(e);  // 通知调用方，而不是 throw
                            }

                        },
                        onError,  // 网络/HTTP 错误统一走 onError,
                        onDone
                );

    }
}
