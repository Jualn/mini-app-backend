package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentImportSseMvcTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void completeStreamHasOrderedNamedFramesCountsAndEchoesRevision() throws Exception {
        String source = "Campus notice";
        String model = "{\"suggestions\":[{\"target\":\"/title\",\"value\":\"Campus notice\",\"excerpts\":[\"Campus notice\"]}]}";
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        MockMvc mvc = mvc(prompt -> {
            capturedPrompt.set(prompt);
            return model;
        });

        MvcResult initial = mvc.perform(multipart("/test/document-import")
                        .file(new MockMultipartFile("file", "notice.pdf", "application/pdf", pdf(source)))
                        .param("targetType", "ACTIVITY").param("baseRevision", "revision-7")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("event:started", "event:warning", "event:suggestion", "event:completed");
        assertThat(body.indexOf("event:started")).isLessThan(body.indexOf("event:suggestion"));
        assertThat(body).contains("\"baseRevision\":\"revision-7\"");
        assertThat(body).contains("\"sequence\":1", "\"sequence\":4");
        assertThat(body).contains("\"suggestionCount\":2", "\"warningCount\":2", "文档提取原文（需人工核对）", "\"target\":\"/sections\"");
        assertThat(body).doesNotContain("event:done", "event:error", "{\"f\"");
        assertThat(capturedPrompt.get()).contains("targetType: ACTIVITY", "实际文档提取范围：PDF_TEXT_LAYER",
                "BEGIN UNTRUSTED DOCUMENT DATA", source);
    }

    @Test
    void publicEventRecoveryUsesOneModelCallAndTheSameAtomicSectionShape() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MockMvc mvc = mvc(prompt -> {
            calls.incrementAndGet();
            return "{\"suggestions\":[]}";
        });
        MvcResult initial = mvc.perform(multipart("/test/document-import")
                        .file(new MockMultipartFile("file", "notice.pdf", "application/pdf", pdf("Public notice")))
                        .param("targetType", "PUBLIC_EVENT").param("baseRevision", "public-r1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(initial)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"targetType\":\"PUBLIC_EVENT\"", "\"target\":\"/sections\"",
                "文档提取原文（需人工核对）", "Public notice", "\"suggestionCount\":1", "event:completed");
        assertThat(body).doesNotContain("registrationMode", "event:failed");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidModelOutputEndsOnlyWithFailedAndDoesNotLeakRawOutput() throws Exception {
        MockMvc mvc = mvc(prompt -> "provider secret malformed output");
        MvcResult initial = mvc.perform(multipart("/test/document-import")
                        .file(new MockMultipartFile("file", "notice.pdf", "application/pdf", pdf("Notice")))
                        .param("targetType", "PUBLIC_EVENT").param("baseRevision", "r1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(initial)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:failed", "\"code\":\"AI_OUTPUT_INVALID\"", "\"stage\":\"GENERATION\"");
        assertThat(body).doesNotContain("event:completed", "provider secret malformed output");
    }

    private MockMvc mvc(DocumentImportAiGateway gateway) {
        DocumentImportService service = new DocumentImportService(new DocumentImportFileReader(),
                new DocumentImportPromptBuilder(), gateway, new DocumentImportSuggestionValidator(mapper), mapper,
                new DocumentImportEmitterFactory(),
                new TaskExecutorAdapter(Runnable::run), new DocumentImportProperties(), org.mockito.Mockito.mock(DocumentImportAuthorizer.class));
        return MockMvcBuilders.standaloneSetup(new TestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void acceptedBodyCoverageWarningUsesExistingSseWithoutAddingPublicFields() throws Exception {
        String source = "一、材料提交\n学生组须在指定时间提交完整报名材料。\n二、工作要求\n各单位应组织宣传并总结本单位工作成效。";
        String model = mapper.writeValueAsString(java.util.Map.of("suggestions", java.util.List.of(java.util.Map.of(
                "target", "/sections", "value", java.util.List.of(java.util.Map.of("title", "材料提交", "content", "学生组须在指定时间提交完整报名材料。", "format", "PLAIN_TEXT")),
                "excerpts", java.util.List.of("学生组须在指定时间提交完整报名材料。")))));
        byte[] document;
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var output = new ByteArrayOutputStream()) {
            for (String line : source.split("\\n")) word.createParagraph().createRun().setText(line);
            word.write(output); document = output.toByteArray();
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MockMvc mvc = mvc(prompt -> { calls.incrementAndGet(); return model; });
        MvcResult initial = mvc.perform(multipart("/test/document-import")
                .file(new MockMultipartFile("file", "notice.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", document))
                .param("targetType", "PUBLIC_EVENT").param("baseRevision", "coverage-r1").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("INCOMPLETE_CONFIGURATION", "\"target\":\"/sections\"", "原文补充",
                "各单位应组织宣传并总结本单位工作成效。", "event:completed", "\"suggestionCount\":1");
        assertThat(body).doesNotContain("contentCoverage", "bodyMode", "文档提取原文（需人工核对）");
        assertThat(calls.get()).isEqualTo(1);
    }

    private byte[] pdf(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
        }
        return output.toByteArray();
    }

    @RestController
    static class TestController {
        private final DocumentImportService service;

        TestController(DocumentImportService service) {
            this.service = service;
        }

        @PostMapping(value = "/test/document-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        ResponseEntity<SseEmitter> execute(@RequestParam MultipartFile file,
                                           @RequestParam DocumentImportTarget targetType,
                                           @RequestParam String baseRevision) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(service.importDocument(file, targetType, baseRevision));
        }
    }
}
