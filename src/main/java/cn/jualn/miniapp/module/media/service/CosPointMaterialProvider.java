package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.*;
import com.tencent.cloud.CosStsClient;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static cn.jualn.miniapp.module.media.service.PointMaterialProblems.*;

/**
 * Uses the existing COS SDK/credentials, with a separately configured private bucket and exact PUT policy.
 */
@Service
public class CosPointMaterialProvider implements PointMaterialProvider {
    private final COSClient cos;
    private final CosProperties properties;
    private final ObjectMapper json;
    private final ObjectProvider<PointMaterialSafety> safety;
    private final String bucket;
    private PointMaterialSafety directSafety;
    private ManagedArticleMediaInspector directVideoInspector;
    private ObjectProvider<ManagedArticleMediaInspector> videoInspectors;

    @Autowired
    void videoInspectors(ObjectProvider<ManagedArticleMediaInspector> inspectors) {
        this.videoInspectors = inspectors;
    }

    @Autowired
    public CosPointMaterialProvider(COSClient cos, CosProperties properties, ObjectMapper json, ObjectProvider<PointMaterialSafety> safety,
                                    @Value("${app.points.private-bucket:}") String bucket) {
        this.cos = cos;
        this.properties = properties;
        this.json = json;
        this.safety = safety;
        this.bucket = bucket;
    }

    /**
     * Shared private transport; each business owner supplies its own bucket and safety evidence.
     */
    public CosPointMaterialProvider(COSClient cos, CosProperties properties, ObjectMapper json, PointMaterialSafety safety, String bucket, ManagedArticleMediaInspector inspector) {
        this.cos = cos;
        this.properties = properties;
        this.json = json;
        this.safety = null;
        this.bucket = bucket;
        this.directSafety = safety;
        this.directVideoInspector = inspector;
    }

    public void requireReady() {
        ready();
    }

    private void ready() {
        if (bucket == null || bucket.isBlank() || bucket.equals(properties.getBucket()) || !bucket.matches("[a-z0-9-]+-[0-9]+"))
            throw unavailable(Dependency.PRIVATE_MEDIA);
        if (directSafety == null && (safety == null || safety.getIfAvailable() == null))
            throw unavailable(Dependency.CONTENT_SAFETY);
        try {
            privateAcl(cos.getBucketAcl(bucket));
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(Dependency.PRIVATE_MEDIA);
        }
    }

    private void privateAcl(AccessControlList acl) {
        if (acl == null || acl.getOwner() == null || acl.getOwner().getId() == null
                || acl.getGrantsAsList().stream().anyMatch(g -> !acl.getOwner().getId().equals(g.getGrantee().getIdentifier())))
            throw unavailable(Dependency.PRIVATE_MEDIA);
    }

    @Override
    public Credentials credentials(String upload, List<String> keys, LocalDateTime expires) {
        ready();
        try {
            String appid = bucket.substring(bucket.lastIndexOf('-') + 1);
            var resources = keys.stream().map(key -> "qcs::cos:" + properties.getRegion() + ":uid/" + appid + ":" + bucket + "/" + key).toList();
            var statement = new ArrayList<Map<String, Object>>();

            // 1. 普通 PUT 上传 + 初始化分块上传
            statement.add(Map.of(
                    "effect", "allow",
                    "action", List.of(
                            "name/cos:PutObject",
                            "name/cos:InitiateMultipartUpload"
                    ),
                    "resource", resources,
                    "condition", Map.of(
                            "string_equal", Map.of(
                                    "cos:x-cos-acl", "private",
                                    "cos:x-cos-forbid-overwrite", "true"
                            )
                    )
            ));

            // 2. 上传视频分块
            statement.add(Map.of(
                    "effect", "allow",
                    "action", List.of(
                            "name/cos:UploadPart"
                    ),
                    "resource", resources
            ));

            // 3. 完成分块上传
            statement.add(Map.of(
                    "effect", "allow",
                    "action", List.of(
                            "name/cos:CompleteMultipartUpload"
                    ),
                    "resource", resources,
                    "condition", Map.of(
                            "string_equal", Map.of(
                                    "cos:x-cos-forbid-overwrite", "true"
                            )
                    )
            ));

            // ACL grants are separate from canned ACL; explicitly forbid all of them, even alongside private.
            for (String header : List.of(
                    "x-cos-grant-read",
                    "x-cos-grant-write",
                    "x-cos-grant-read-acp",
                    "x-cos-grant-write-acp",
                    "x-cos-grant-full-control"
            ))
                statement.add(Map.of(
                        "effect", "deny",
                        "action", List.of(
                                "name/cos:PutObject",
                                "name/cos:InitiateMultipartUpload"
                        ),
                        "resource", resources,
                        "condition", Map.of(
                                "null", Map.of(
                                        "cos:" + header, "false"))));
            var config = new TreeMap<String, Object>();
            config.put("secretId", properties.getSecretId());
            config.put("secretKey", properties.getSecretKey());
            config.put("durationSeconds", 840);
            config.put("policy", json.writeValueAsString(Map.of("version", "2.0", "statement", statement)));
            config.put("bucket", bucket);
            config.put("region", properties.getRegion());
            var response = json.valueToTree(CosStsClient.getCredential(config));
            var secret = response.path("credentials");
            long expired = response.path("expiredTime").asLong();
            // STS may expire earlier than the requested wall clock bound; it must never outlive it.
            if (expired > expires.toEpochSecond(ZoneOffset.UTC) || expired <= Instant.now().getEpochSecond())
                throw unavailable(Dependency.PRIVATE_MEDIA);
            return new Credentials(bucket, properties.getRegion(), keys, secret.path("tmpSecretId").asText(null), secret.path("tmpSecretKey").asText(null), secret.path("sessionToken").asText(null), expired, null, upload, LocalDateTime.ofEpochSecond(expired, 0, ZoneOffset.UTC));
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(Dependency.PRIVATE_MEDIA);
        }
    }

    @Override
    public void retainAndInspect(String source, String retained, long owner, Declaration declaration) {
        retainAndInspect(source, retained, owner, declaration, 10485760);
    }

    public void retainAndInspect(String source, String retained, long owner, Declaration declaration, long maximumBytes) {
        ready();
        Path snapshot = null;
        try {
            privateAcl(cos.getObjectAcl(bucket, source));
            var request = new CopyObjectRequest(bucket, source, bucket, retained);
            request.setCannedAccessControlList(CannedAccessControlList.Private);
            request.putCustomRequestHeader("x-cos-forbid-overwrite", "true");
            try {
                cos.copyObject(request);
            } catch (com.qcloud.cos.exception.CosServiceException e) {
                if (e.getStatusCode() != 409) throw e;
            }
            privateAcl(cos.getObjectAcl(bucket, retained));
            var metadata = cos.getObjectMetadata(bucket, retained);
            if (metadata.getContentLength() != declaration.sizeBytes() || !declaration.contentType().equals(metadata.getContentType()))
                throw PointMaterialService.invalidReference("uploadId");
            snapshot = Files.createTempFile("jualn-points-private-", ".material");
            try (var in = cos.getObject(bucket, retained).getObjectContent(); var out = Files.newOutputStream(snapshot)) {
                byte[] buffer = new byte[65536];
                long count = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    count += read;
                    if (count > maximumBytes) throw PointMaterialService.invalidReference("uploadId");
                    out.write(buffer, 0, read);
                }
                if (count != declaration.sizeBytes()) throw PointMaterialService.invalidReference("uploadId");
            }
            if ("VIDEO".equals(declaration.kind())) inspectVideo(snapshot, declaration);
            else inspect(snapshot, declaration);
            (directSafety == null ? safety.getObject() : directSafety).requireSafe(owner, retained, snapshot, declaration);
        } catch (ContractProblemException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unavailable(Dependency.PRIVATE_MEDIA);
        } finally {
            if (snapshot != null) try {
                Files.deleteIfExists(snapshot);
            } catch (IOException e) {
                throw unavailable(Dependency.PRIVATE_MEDIA);
            }
        }
    }

    static void inspect(Path file, Declaration d) throws IOException {
        if ("VIDEO".equals(d.kind())) throw unavailable(Dependency.PRIVATE_MEDIA);
        if ("IMAGE".equals(d.kind())) {
            try (var in = ImageIO.createImageInputStream(file.toFile())) {
                var readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) {
                    if ("image/webp".equals(d.contentType())) throw unavailable(Dependency.CONTENT_SAFETY);
                    throw PointMaterialService.invalidReference("uploadId");
                }
                var reader = readers.next();
                try {
                    reader.setInput(in);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    String mime = switch (format) {
                        case "jpeg", "jpg" -> "image/jpeg";
                        case "png" -> "image/png";
                        case "webp" -> "image/webp";
                        default -> "unsupported";
                    };
                    if (!mime.equals(d.contentType()) || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0 || (long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000L || reader.read(0) == null)
                        throw PointMaterialService.invalidReference("uploadId");
                } finally {
                    reader.dispose();
                }
            } catch (IIOException malformed) {
                throw PointMaterialService.invalidReference("uploadId");
            }
        } else if ("PDF".equals(d.kind())) {
            try (var document = Loader.loadPDF(file.toFile(), org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
                var catalog = document.getDocumentCatalog().getCOSObject();
                if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 200
                        || catalog.containsKey(org.apache.pdfbox.cos.COSName.getPDFName("OpenAction"))
                        || catalog.containsKey(org.apache.pdfbox.cos.COSName.getPDFName("AA"))
                        || catalog.containsKey(org.apache.pdfbox.cos.COSName.getPDFName("Names")))
                    throw PointMaterialService.invalidReference("uploadId");
            } catch (IOException e) {
                throw PointMaterialService.invalidReference("uploadId");
            }
        } else if ("application/msword".equals(d.contentType())) {
            try (var input = Files.newInputStream(file); var document = new HWPFDocument(input)) {
                if (document.getRange().text().length() > 1_000_000)
                    throw PointMaterialService.invalidReference("uploadId");
            } catch (IOException | RuntimeException e) {
                throw PointMaterialService.invalidReference("uploadId");
            }
        } else {
            // Bound inflated DOCX bytes before invoking POI; no process-wide parser settings are changed.
            try (var raw = Files.newInputStream(file); var zip = new java.util.zip.ZipInputStream(raw)) {
                int entries = 0;
                long inflated = 0;
                byte[] buffer = new byte[8192];
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (++entries > 1000) throw PointMaterialService.invalidReference("uploadId");
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        inflated += read;
                        if (inflated > 50_000_000L) throw PointMaterialService.invalidReference("uploadId");
                    }
                }
                if (entries == 0) throw PointMaterialService.invalidReference("uploadId");
            } catch (IOException e) {
                throw PointMaterialService.invalidReference("uploadId");
            }
            try (var input = Files.newInputStream(file); var document = new XWPFDocument(input)) {
                if (document.getPackage().getParts().stream().anyMatch(p -> p.getContentType().toLowerCase(Locale.ROOT).contains("macroenabled") || p.getPartName().getName().contains("vbaProject")))
                    throw PointMaterialService.invalidReference("uploadId");
            } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException | RuntimeException e) {
                throw PointMaterialService.invalidReference("uploadId");
            }
        }
    }

    void inspectVideo(Path file, Declaration declaration) throws IOException {
        var inspector = directVideoInspector != null ? directVideoInspector : videoInspectors == null ? null : videoInspectors.getIfAvailable();
        if (inspector == null) throw unavailable(Dependency.PRIVATE_MEDIA);
        if (!"video/mp4".equals(declaration.contentType())) throw PointMaterialService.invalidReference("uploadId");
        try {
            inspector.video(file, declaration.sizeBytes());
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            if (failure.getStatus().is5xxServerError()) throw unavailable(Dependency.PRIVATE_MEDIA);
            throw PointMaterialService.invalidReference("uploadId");
        }
    }

    @Override
    public Access access(String retained, LocalDateTime expires) {
        ready();
        try {
            privateAcl(cos.getObjectAcl(bucket, retained));
            var request = new GeneratePresignedUrlRequest(bucket, retained, HttpMethodName.GET);
            request.setExpiration(Date.from(expires.toInstant(ZoneOffset.UTC)));
            request.setResponseHeaders(new ResponseHeaderOverrides().withCacheControl("no-store"));
            String url = cos.generatePresignedUrl(request).toExternalForm();
            if (!url.startsWith("https://")) throw unavailable(Dependency.PRIVATE_MEDIA);
            return new Access(url, expires);
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(Dependency.PRIVATE_MEDIA);
        }
    }

    @Override
    public void delete(String key) {
        ready();
        cos.deleteObject(bucket, key);
    }
}
