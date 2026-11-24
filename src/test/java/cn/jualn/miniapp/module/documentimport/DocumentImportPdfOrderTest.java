package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.util.DocParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportPdfOrderTest {
    @Test
    void importReaderUsesPositionOrderWithoutChangingSharedParser() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                for (int y : new int[]{600, 700}) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, y);
                    stream.showText(y == 600 ? "Bottom" : "Top");
                    stream.endText();
                }
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        assertThat(DocParser.extract(bytes, DocParser.Format.PDF)).containsSubsequence("Bottom", "Top");
        assertThat(new DocumentImportFileReader().read(new org.springframework.mock.web.MockMultipartFile(
                "file", "order.pdf", "application/pdf", bytes)).text()).containsSubsequence("Top", "Bottom");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            assertThat(stripper.getSortByPosition()).isFalse();
            stripper.setSortByPosition(true);
            assertThat(stripper.getText(document)).containsSubsequence("Top", "Bottom");
        }
    }
}
