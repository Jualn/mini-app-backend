package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.util.DocParser;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class DocumentImportFileReader {
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP = {'P', 'K'};
    private static final byte[] OLE = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private static final Set<String> GENERIC_MIME = Set.of("", "application/octet-stream");

    public ParsedDocument read(MultipartFile file) {
        if (file == null) throw invalid(HttpStatus.BAD_REQUEST, "/problems/validation-error", "缺少 file");
        if (file.isEmpty()) throw invalid(HttpStatus.UNPROCESSABLE_ENTITY,
                "/problems/document-import-unreadable", "文件为空或不可读");

        DocParser.Format format;
        try {
            format = DocParser.formatFromFilename(file.getOriginalFilename());
        } catch (IllegalArgumentException exception) {
            throw unsupported("不支持的文件后缀");
        }
        validateMime(format, file.getContentType());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw unreadable();
        }
        if (!matches(bytes, signature(format))) throw unsupported("文件内容与后缀不一致");
        validateContainer(format, bytes);

        String text;
        try {
            text = DocParser.extract(bytes, format);
        } catch (Exception exception) {
            throw unreadable();
        }
        text = normalizeLineEndings(text);
        if (text.isBlank()) throw invalid(HttpStatus.UNPROCESSABLE_ENTITY,
                "/problems/document-import-no-text", "文件没有可提取文本");
        return new ParsedDocument(text, format.extractionScope());
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void validateMime(DocParser.Format format, String raw) {
        String mime = raw == null ? "" : raw.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (GENERIC_MIME.contains(mime)) return;
        boolean valid = switch (format) {
            case PDF -> "application/pdf".equals(mime);
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
            case DOC -> "application/msword".equals(mime);
        };
        if (!valid) throw unsupported("文件 MIME 与后缀不一致");
    }

    private byte[] signature(DocParser.Format format) {
        return switch (format) {
            case PDF -> PDF;
            case DOCX -> ZIP;
            case DOC -> OLE;
        };
    }

    private void validateContainer(DocParser.Format format, byte[] bytes) {
        if (format == DocParser.Format.DOCX) {
            boolean contentTypes = false;
            boolean documentXml = false;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                    if ("word/document.xml".equals(entry.getName())) documentXml = true;
                }
            } catch (IOException exception) {
                throw unreadable();
            }
            if (!contentTypes || !documentXml) throw unsupported("文件不是 DOCX 文档");
        }
        if (format == DocParser.Format.DOC) {
            try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                if (!filesystem.getRoot().hasEntry("WordDocument")) {
                    throw unsupported("文件不是 DOC 文档");
                }
            } catch (ContractProblemException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw unreadable();
            }
        }
    }

    private boolean matches(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private ContractProblemException unsupported(String detail) {
        return invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "/problems/document-import-unsupported-format", detail);
    }

    private ContractProblemException unreadable() {
        return invalid(HttpStatus.UNPROCESSABLE_ENTITY,
                "/problems/document-import-unreadable", "文件损坏、加密或不可读");
    }

    private ContractProblemException invalid(HttpStatus status, String type, String detail) {
        return new ContractProblemException(status, type, detail);
    }

    public record ParsedDocument(String text, String extractionScope) {
    }
}
