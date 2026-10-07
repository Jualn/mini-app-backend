package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/admin/document-imports")
@RequiredArgsConstructor
public class DocumentImportController {
    private final DocumentImportService service;
    private final DocumentImportAuthorizer authorizer;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> importDocument(
            @RequestParam MultipartFile file,
            @RequestParam DocumentImportTarget targetType,
            @RequestParam String baseRevision,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        validateRequest(baseRevision, lastEventId);
        authorizer.requireEdit(targetType);
        response.setCharacterEncoding("UTF-8");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(service.importDocument(file, targetType, baseRevision));
    }

    private void validateRequest(String baseRevision, String lastEventId) {
        if (baseRevision == null || baseRevision.isBlank() || baseRevision.length() > 128) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "body", "baseRevision", "INVALID", "baseRevision 长度必须为 1 到 128"));
        }
        if (lastEventId != null && !lastEventId.isBlank()) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "header", "Last-Event-ID", "UNSUPPORTED", "不支持 SSE 重连或重放"));
        }
    }
}
