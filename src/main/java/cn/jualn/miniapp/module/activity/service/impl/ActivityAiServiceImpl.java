package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.common.util.DocParser;
import cn.jualn.miniapp.module.activity.ai.prompt.ActivityPromptBuilder;
import cn.jualn.miniapp.module.activity.bo.ActivityUploadBO;
import cn.jualn.miniapp.module.activity.service.ActivityAiService;
import cn.jualn.miniapp.third.ai.client.DeepSeekClient;
import cn.jualn.miniapp.third.ai.parser.DeepSeekStreamParser;
import cn.jualn.miniapp.third.ai.task.AiTaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ActivityAiServiceImpl implements ActivityAiService {

    private final AiTaskStore aiTaskStore;
    private final DeepSeekClient deepSeekClient;
    private final ActivityPromptBuilder activityPromptBuilder;
    private final DeepSeekStreamParser deepSeekStreamParser;
    private final ObjectMapper objectMapper;

    @Override
    public ActivityUploadBO upload(MultipartFile file) {
        String text;
        try {
            text = DocParser.extract(file);
        } catch (Exception e) {
            throw new RuntimeException("文件解析失败", e);
        }

        if (text.length() < 50) {
            throw new BusinessException(ResultCode.ACTIVITY_FILE_PARSE_ERROR, "文件解析失败，请手动填写！");
        }

        return ActivityUploadBO.builder()
                .taskId(aiTaskStore.save(text)).build();
    }

    @Override
    public SseEmitter stream(String taskId, String skipped) {
        SseEmitter emitter = new SseEmitter(90_000L);
        String docText = aiTaskStore.get(taskId);

        if (docText == null) {
            emitter.completeWithError(new IllegalArgumentException("任务不存在或已过期"));
            return emitter;
        }

        Map<String, Object> skippedFields;
        try {
            skippedFields = objectMapper.readValue(skipped, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            skippedFields = Map.of();  // 解析失败当空处理，不影响主流程
        }

        callDeepSeekStream(docText, skippedFields, emitter);

        return emitter;
    }

    private void callDeepSeekStream(String docText, Map<String, Object> skipped, SseEmitter emitter) {

        String prompt = activityPromptBuilder.build(docText, skipped);
        StringBuilder lineBuffer = new StringBuilder();

        // 统一的错误处理，无论同步异步都走这里
        Consumer<Throwable> onError = error -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(error);
        };

        deepSeekClient.streamChat(
                prompt,
                chunk -> {
                    lineBuffer.append(chunk);
                    String completeLine;
                    while ((completeLine = deepSeekStreamParser.pollLine(lineBuffer)) != null) {
                        if (completeLine.startsWith("{")) {
                            try {
                                emitter.send(SseEmitter.event().data(completeLine));
                            } catch (IOException e) {
                                onError.accept(e);
                                return;
                            }
                        }
                    }
                },
                () -> {  // onDone
                    String remaining = lineBuffer.toString().trim();
                    try {
                        if (remaining.startsWith("{")) {
                            emitter.send(SseEmitter.event().data(remaining));

                        }

                        emitter.send(SseEmitter.event().name("done").data("{}"));
                        emitter.complete();
                        aiTaskStore.remove(remaining);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                onError
        );

    }
}
