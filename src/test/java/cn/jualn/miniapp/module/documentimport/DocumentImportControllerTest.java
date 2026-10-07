package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentImportControllerTest {
    private final DocumentImportService service = mock(DocumentImportService.class);
    private final DocumentImportAuthorizer authorizer = mock(DocumentImportAuthorizer.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new DocumentImportController(service, authorizer))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void validatesRevisionAndLastEventIdBeforeStartingWork() throws Exception {
        var file = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-x".getBytes());
        mvc.perform(multipart("/v1/admin/document-imports").file(file)
                        .param("targetType", "ACTIVITY").param("baseRevision", " ")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("/problems/validation-error"));
        mvc.perform(multipart("/v1/admin/document-imports").file(file)
                        .param("targetType", "ACTIVITY").param("baseRevision", "r1")
                        .header("Last-Event-ID", "old:3").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest());
        verify(service, never()).importDocument(any(), any(), any());
    }

    @Test
    void rejectsMissingFileWrongMultipartTypeAndUnacceptableResponse() throws Exception {
        mvc.perform(multipart("/v1/admin/document-imports")
                        .param("targetType", "ACTIVITY").param("baseRevision", "r1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("/problems/validation-error"));
        mvc.perform(post("/v1/admin/document-imports").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnsupportedMediaType());
        var file = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-x".getBytes());
        mvc.perform(multipart("/v1/admin/document-imports").file(file)
                        .param("targetType", "ACTIVITY").param("baseRevision", "r1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void checksTargetPermissionBeforeParsingOrAi() throws Exception {
        doThrow(new ContractProblemException(org.springframework.http.HttpStatus.FORBIDDEN,
                "/problems/forbidden", "无权限访问"))
                .when(authorizer).requireEdit(DocumentImportTarget.PUBLIC_EVENT);
        var file = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-x".getBytes());
        mvc.perform(multipart("/v1/admin/document-imports").file(file)
                        .param("targetType", "PUBLIC_EVENT").param("baseRevision", "r1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
        verify(authorizer).requireEdit(DocumentImportTarget.PUBLIC_EVENT);
        verify(service, never()).importDocument(any(), any(), any());
    }

    @Test
    void routesBothTargetsAndOldUploadGetEndpointsAreGone() throws Exception {
        SseEmitter emitter = new SseEmitter();
        emitter.complete();
        when(service.importDocument(any(), eq(DocumentImportTarget.ACTIVITY), eq("r1"))).thenReturn(emitter);
        var file = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-x".getBytes());
        mvc.perform(multipart("/v1/admin/document-imports").file(file)
                        .param("targetType", "ACTIVITY").param("baseRevision", "r1")
                        .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isOk());
        verify(authorizer).requireEdit(DocumentImportTarget.ACTIVITY);
        org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("cn.jualn.miniapp.module.activity.controller.ActivityController"));
    }
}
