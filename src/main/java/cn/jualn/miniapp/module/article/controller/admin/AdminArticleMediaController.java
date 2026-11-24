package cn.jualn.miniapp.module.article.controller.admin;
import cn.jualn.miniapp.module.article.service.ArticleAuthorization;
import cn.jualn.miniapp.module.article.dto.ArticleUploadRequest;
import cn.jualn.miniapp.module.media.service.MediaService;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.NumberSerializer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class AdminArticleMediaController {
 private final ArticleAuthorization authorization;
 private final MediaService media;
 public record Credentials(String bucket,String region,List<String> objectKeys,String tmpSecretId,String tmpSecretKey,String sessionToken,
                           @JsonSerialize(using=EpochSecondsSerializer.class) long expiredTime,String customDomain) {}
 public static final class EpochSecondsSerializer extends NumberSerializer {
  public EpochSecondsSerializer() { super(Long.class); }
 }
 @PostMapping("/v1/admin/media/article-upload-credentials")
 public ResponseEntity<Credentials> upload(@RequestBody ArticleUploadRequest request) {
  long operator=authorization.require(ArticleAuthorization.Capability.MEDIA);
  var c=media.generateArticleUploadCredential(request.files(),operator);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Credentials(c.getBucket(),c.getRegion(),c.getObjectKeys(),c.getTmpSecretId(),c.getTmpSecretKey(),c.getSessionToken(),c.getExpiredTime(),c.getCustomDomain()));
 }
}
