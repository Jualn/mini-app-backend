package cn.jualn.miniapp.common.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.stream.Collectors;

/** Shared PDF/DOCX/DOC body-text extraction. It deliberately does not perform OCR. */
public final class DocParser {
    public enum Format {
        PDF("PDF_TEXT_LAYER"), DOCX("DOCX_BODY_PARAGRAPHS"), DOC("DOC_BODY_TEXT");

        private final String extractionScope;

        Format(String extractionScope) {
            this.extractionScope = extractionScope;
        }

        public String extractionScope() {
            return extractionScope;
        }
    }

    private DocParser() {
    }

    public static String extract(MultipartFile file) throws IOException {
        return extract(file.getBytes(), formatFromFilename(file.getOriginalFilename()));
    }

    public static String extract(byte[] bytes, Format format) throws IOException {
        if (format == Format.PDF) {
            try (PDDocument document = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(document).trim();
            }
        }
        if (format == Format.DOCX) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                return document.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .collect(Collectors.joining("\n")).trim();
            }
        }
        if (format == Format.DOC) {
            try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
                return document.getRange().text().trim();
            }
        }
        throw new IllegalArgumentException("仅支持 PDF / DOCX / DOC 格式");
    }

    public static Format formatFromFilename(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return Format.PDF;
        if (name.endsWith(".docx")) return Format.DOCX;
        if (name.endsWith(".doc")) return Format.DOC;
        throw new IllegalArgumentException("仅支持 PDF / DOCX / DOC 格式");
    }
}
