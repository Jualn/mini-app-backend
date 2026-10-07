package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentImportFileReaderTest {
    private final DocumentImportFileReader reader = new DocumentImportFileReader();

    @Test
    void readsPdfTextLayerAndDocxBodyParagraphsIncludingUppercaseSuffix() throws Exception {
        var pdf = new MockMultipartFile("file", "NOTICE.PDF", "application/pdf", pdf("Notice text"));
        var pdfResult = reader.read(pdf);
        assertThat(pdfResult.extractionScope()).isEqualTo("PDF_TEXT_LAYER");
        assertThat(pdfResult.text()).contains("Notice text");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Body paragraph");
            document.write(bytes);
        }
        var docx = new MockMultipartFile("file", "notice.DOCX", "application/octet-stream", bytes.toByteArray());
        var docxResult = reader.read(docx);
        assertThat(docxResult.extractionScope()).isEqualTo("DOCX_BODY_PARAGRAPHS");
        assertThat(docxResult.text()).isEqualTo("Body paragraph");
    }

    @Test
    void distinguishesEmptyNoTextAndSignatureOrMimeMismatch() throws Exception {
        assertProblem(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]), 422,
                "/problems/document-import-unreadable");
        assertProblem(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("")), 422,
                "/problems/document-import-no-text");
        assertProblem(new MockMultipartFile("file", "a.docx", "application/octet-stream", pdf("text")), 415,
                "/problems/document-import-unsupported-format");
        assertProblem(new MockMultipartFile("file", "a.pdf", "text/plain", pdf("text")), 415,
                "/problems/document-import-unsupported-format");
    }

    private void assertProblem(MockMultipartFile file, int status, String type) {
        ContractProblemException error = assertThrows(ContractProblemException.class, () -> reader.read(file));
        assertThat(error.getStatus().value()).isEqualTo(status);
        assertThat(error.getType()).isEqualTo(type);
    }

    private byte[] pdf(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
        }
        return output.toByteArray();
    }
}
