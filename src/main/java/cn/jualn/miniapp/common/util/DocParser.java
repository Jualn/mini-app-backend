package cn.jualn.miniapp.common.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 文档解析工具类，支持 PDF、DOCX、DOC 格式的文本提取
 */
public class DocParser {

    /**
     * 从上传的文件中提取文本内容
     * 支持 PDF、DOCX、DOC 格式
     * @param file 上传的文件
     * @return 提取的文本内容
     * @throws IOException 如果文件读取失败或格式不受支持
     */
    public static String extract(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        if (name.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                return new PDFTextStripper().getText(doc).trim();
            }
        }

        if (name.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                return doc.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .collect(Collectors.joining("\n")).trim();
            }
        }

        if (name.endsWith(".doc")) {
            try (HWPFDocument doc = new HWPFDocument(file.getInputStream())) {
                return doc.getRange().text().trim();
            }
        }

        throw new IllegalArgumentException("仅支持 PDF / DOCX / DOC 格式");
    }
}
