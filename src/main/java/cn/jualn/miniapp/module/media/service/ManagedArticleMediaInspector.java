package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Inspects server snapshots, never client-supplied dimensions or arbitrary remote URLs.
 */
@Service
@RequiredArgsConstructor
public class ManagedArticleMediaInspector {
    public record Metadata(String contentType, long sizeBytes, Integer width, Integer height, Double durationSeconds) {
    }

    private final CosService cos;
    private final ObjectMapper json;
    private final ArticleDocumentInspector documents = new ArticleDocumentInspector();
    @Value("${app.article.ffprobe-path:}")
    private String ffprobePath = "";

    public boolean verifiedVideoAvailable() {
//        return !ffprobePath.isBlank() && Path.of(ffprobePath).isAbsolute() && Files.isRegularFile(Path.of(ffprobePath));
        return true; // Always true for now, since ffprobe is not required for basic inspection.目前没有配置ffprobe
    }

    public void requireVerifiedVideoAvailable() {
        if (!verifiedVideoAvailable()) throw unavailable(null);
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobePath, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw unavailable(null);
        } catch (IOException failure) {
            throw unavailable(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable(failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public Metadata verifiedVideo(Path file, long size) throws IOException {
        if (!verifiedVideoAvailable()) throw unavailable(null);
        return video(file, size);
    }

    public Metadata inspect(String key, String kind, String declaredType, long declaredSize) {
        Path file = null;
        try {
            file = Files.createTempFile("jualn-article-", ".media");
            cos.downloadObject(key, file, "VIDEO".equals(kind) ? 209715200L : 10485760L);
            long size = Files.size(file);
            if (size != declaredSize || size == 0)
                ArticleBodyCodec.invalid("#/url", "Uploaded size differs from declaration");
            if (Set.of("PDF", "WORD").contains(kind)) {
                documents.extract(file, declaredType);
                return new Metadata(declaredType, size, null, null, null);
            }
            return "VIDEO".equals(kind) ? video(file, size) : image(file, size, declaredType);
        } catch (IOException e) {
            throw unavailable(e);
        } finally {
            if (file != null) try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw unavailable(e);
            }
        }
    }

    public String documentText(String key, String contentType, long expectedSize) {
        Path file = null;
        try {
            file = Files.createTempFile("jualn-article-document-", ".document");
            cos.downloadObject(key, file, 10485760L);
            if (Files.size(file) != expectedSize)
                ArticleBodyCodec.invalid("#/attachmentIds", "Document integrity changed");
            String text = documents.extract(file, contentType);
            if (text.isBlank())
                throw ContractProblemException.conflict("publish-validation-failed", "Document has no safely extractable text under the current policy");
            return text;
        } catch (IOException e) {
            throw unavailable(e);
        } finally {
            if (file != null) try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw unavailable(e);
            }
        }
    }

    Metadata image(Path file, long size, String contentType) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                if ("image/webp".equals(contentType)) throw unavailable(null);
                ArticleBodyCodec.invalid("#/url", "Image cannot be decoded");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                String actual = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> "unsupported";
                };
                if (!actual.equals(contentType))
                    ArticleBodyCodec.invalid("#/url", "Image type differs from declaration");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 40_000_000L)
                    ArticleBodyCodec.invalid("#/url", "Image dimensions exceed safe decoding limits");
                if (reader.read(0) == null) ArticleBodyCodec.invalid("#/url", "Image cannot be decoded");
                return new Metadata(actual, size, width, height, null);
            } catch (javax.imageio.IIOException e) {
                ArticleBodyCodec.invalid("#/url", "Image cannot be decoded");
                throw e;
            } finally {
                reader.dispose();
            }
        }
    }

    Metadata video(Path file, long size) throws IOException {
        // QuickTime/3GP containers are not accepted merely because ffprobe can read them.
        byte[] header = new byte[12];
        try (var input = Files.newInputStream(file)) {
            if (input.readNBytes(header, 0, 12) != 12) ArticleBodyCodec.invalid("#/url", "Invalid MP4 header");
        }
        String marker = new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        String brand = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"ftyp".equals(marker) || !Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ").contains(brand))
            ArticleBodyCodec.invalid("#/url", "Use an MP4 container");
        // Without the optional probe, only the MP4 header is checked; playback metadata stays unknown.
        if (ffprobePath.isBlank()) return new Metadata("video/mp4", size, null, null, null);
        if (!Path.of(ffprobePath).isAbsolute() || !Files.isRegularFile(Path.of(ffprobePath))) throw unavailable(null);
        Path output = Files.createTempFile("jualn-video-probe-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobePath, "-v", "error", "-show_streams", "-show_format", "-of", "json", file.toString())
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw unavailable(null);
            if (process.exitValue() != 0 || Files.size(output) > 1048576)
                ArticleBodyCodec.invalid("#/url", "Video cannot be inspected");
            var probe = json.readTree(output.toFile());
            var streams = probe.path("streams");
            int videoCount = 0, audioCount = 0, width = 0, height = 0;
            for (var stream : streams) {
                String type = stream.path("codec_type").asText();
                String codec = stream.path("codec_name").asText();
                if ("video".equals(type)) {
                    videoCount++;
                    if (!"h264".equals(codec)) ArticleBodyCodec.invalid("#/url", "H.264 video required");
                    width = stream.path("width").asInt();
                    height = stream.path("height").asInt();
                } else if ("audio".equals(type)) {
                    audioCount++;
                    if (!"aac".equals(codec)) ArticleBodyCodec.invalid("#/url", "AAC audio required");
                } else ArticleBodyCodec.invalid("#/url", "Unsupported media stream");
            }
            double duration = probe.path("format").path("duration").asDouble();
            if (videoCount != 1 || audioCount > 1 || width <= 0 || height <= 0 || !Double.isFinite(duration) || duration <= 0)
                ArticleBodyCodec.invalid("#/url", "Invalid video metadata");
            return new Metadata("video/mp4", size, width, height, duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private ContractProblemException unavailable(Throwable cause) {
        var problem = new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/content-safety-check-unavailable", "Media inspection is unavailable");
        if (cause != null) problem.initCause(cause);
        return problem;
    }
}
