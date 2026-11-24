package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.common.util.DocParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Private documents stay outside version control; opt-in comparison of actual PDF text layers. */
@EnabledIfSystemProperty(named = "documentImport.pdfDirectory", matches = ".+")
class DocumentImportRealPdfTest {
    @Test
    void compareDefaultAndPositionOrderOnThreeOriginalDocuments() throws Exception {
        Path directory = Path.of(System.getProperty("documentImport.pdfDirectory"));
        Path output = Path.of("target/document-import-pdf-comparison");
        Files.createDirectories(output);
        List<Map<String, Object>> report = new ArrayList<>();
        for (String prefix : List.of("4c67ce08", "261b8ec9", "c7215d6b")) {
            Path sampleFile;
            try (var files = Files.list(Path.of("target/document-import-samples"))) {
                sampleFile = files.filter(path -> path.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
            }
            var sample = new ObjectMapper().readTree(sampleFile.toFile());
            Path pdf = directory.resolve(sample.path("filename").asText());
            byte[] bytes = Files.readAllBytes(pdf);
            String original = normalize(DocParser.extract(bytes, DocParser.Format.PDF));
            assertThat(original).as(prefix + " original PDF corresponds to diagnostic input").isEqualTo(sample.path("extractedText").asText());
            try (var document = Loader.loadPDF(bytes)) {
                PDFTextStripper sorted = new PDFTextStripper();
                sorted.setSortByPosition(true);
                String position = normalize(sorted.getText(document).trim());
                assertThat(new DocumentImportFileReader().read(new MockMultipartFile("file", pdf.getFileName().toString(),
                        "application/pdf", bytes)).text()).isEqualTo(position);
                assertThat(position).isEqualTo(normalize(sorted.getText(document).trim()));
                assertThat(characters(position)).as(prefix + " preserves non-whitespace characters").isEqualTo(characters(original));
                Files.writeString(output.resolve(prefix + "-default.txt"), original);
                Files.writeString(output.resolve(prefix + "-position.txt"), position);
                PDFRenderer renderer = new PDFRenderer(document);
                for (int page = 0; page < document.getNumberOfPages(); page++) {
                    ImageIO.write(renderer.renderImageWithDPI(page, 110), "PNG", output.resolve(prefix + "-page-" + (page + 1) + ".png").toFile());
                }
                if (prefix.equals("4c67ce08")) {
                    assertThat(position).containsSubsequence("二、活动目的", "为牢固树立", "三、主办单位", "信息学科部学生会活动部", "四、参与对象", "信息学科部全体在校学生");
                }
                report.add(Map.of("input", prefix, "pdf", pdf.toAbsolutePath().toString(), "pages", document.getNumberOfPages(),
                        "defaultLength", original.length(), "positionLength", position.length(), "sameCharacters", true));
            }
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("comparison.json").toFile(), report);
    }

    private String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
    private Map<Integer, Long> characters(String text) {
        return text.codePoints().filter(value -> !Character.isWhitespace(value)).boxed()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
    }
}
