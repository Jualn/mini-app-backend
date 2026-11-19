package cn.jualn.miniapp.module.interact.dto.request;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import cn.jualn.miniapp.module.comment.bo.ArticleCommentCreateBO;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record CanonicalCommentRequest(String content,Long parentId,Long replyToCommentId) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static CanonicalCommentRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("content","parentId","replyToCommentId"),Set.of("content"));
  return new CanonicalCommentRequest(ArticleBodyCodec.text(value.get("content"),1,1000,"#/content",true),
   value.has("parentId")?ArticleBodyCodec.id(value.get("parentId"),"#/parentId"):null,
   value.has("replyToCommentId")?ArticleBodyCodec.id(value.get("replyToCommentId"),"#/replyToCommentId"):null);
 }
 public ArticleCommentCreateBO command() { return new ArticleCommentCreateBO(content,parentId,replyToCommentId); }
}
