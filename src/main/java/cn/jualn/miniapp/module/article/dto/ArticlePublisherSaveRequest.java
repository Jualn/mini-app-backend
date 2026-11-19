package cn.jualn.miniapp.module.article.dto;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record ArticlePublisherSaveRequest(String displayName) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static ArticlePublisherSaveRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("displayName"),Set.of("displayName"));
  return new ArticlePublisherSaveRequest(ArticleBodyCodec.text(value.get("displayName"),1,120,"#/displayName",true));
 }
}
