package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.activity.bo.ActivityUploadBO;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ActivityAiService {
    ActivityUploadBO upload(MultipartFile file);

    SseEmitter stream(String taskId, String skipped);
}
