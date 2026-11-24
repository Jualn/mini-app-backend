package cn.jualn.miniapp.module.interact.dto.request;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record CanonicalShareRequest(String platform) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static CanonicalShareRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("platform"),Set.of("platform"));
  String platform=ArticleBodyCodec.text(value.get("platform"),1,30,"#/platform",true);
  if(!Set.of("WECHAT_FRIEND","WECHAT_MOMENTS").contains(platform)) ArticleBodyCodec.invalid("#/platform","Invalid share platform");
  return new CanonicalShareRequest(platform);
 }
}
