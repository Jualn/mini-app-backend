package cn.jualn.miniapp.module.article.dto;
import cn.jualn.miniapp.module.article.bo.ArticleSaveBO;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record ArticleSaveRequest(String title,String summary,Long categoryId,Long publisherId,
 Long coverAttachmentId,JsonNode body,Boolean commentsEnabled,String publisherOrgId, java.util.List<Long> attachmentIds, JsonNode source, boolean sourcePresent) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static ArticleSaveRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("title","summary","categoryId","publisherId","publisherOrgId","coverAttachmentId","body","commentsEnabled","attachmentIds","source"),
   Set.of("title","summary","categoryId","body"));
  if(value.has("publisherId")==value.has("publisherOrgId")) ArticleBodyCodec.invalid("#/publisherOrgId","Exactly one publisher ID namespace is required");
  if(value.has("commentsEnabled") && !value.get("commentsEnabled").isBoolean()) ArticleBodyCodec.invalid("#/commentsEnabled","Boolean required");
  return new ArticleSaveRequest(ArticleBodyCodec.text(value.get("title"),0,120,"#/title",false),
   ArticleBodyCodec.text(value.get("summary"),0,500,"#/summary",false),
   value.get("categoryId").isNull()?null:ArticleBodyCodec.id(value.get("categoryId"),"#/categoryId"),
   value.has("publisherId")?cn.jualn.miniapp.module.article.converter.ArticleConverter.lookupId(ArticleBodyCodec.text(value.get("publisherId"),1,128,"#/publisherId",false)):null,
   value.has("coverAttachmentId")?ArticleBodyCodec.id(value.get("coverAttachmentId"),"#/coverAttachmentId"):null,
   value.get("body"),value.has("commentsEnabled")?value.get("commentsEnabled").booleanValue():null,value.has("publisherOrgId")?ArticleBodyCodec.text(value.get("publisherOrgId"),1,128,"#/publisherOrgId",false):null, value.has("attachmentIds")?cn.jualn.miniapp.module.article.service.ArticleContentFields.files(value.get("attachmentIds")):null, cn.jualn.miniapp.module.article.service.ArticleContentFields.source(value.get("source")),value.has("source"));
 }
 public ArticleSaveBO command() { return new ArticleSaveBO(title,summary,categoryId,publisherId,coverAttachmentId,body,commentsEnabled,publisherOrgId,attachmentIds,source,sourcePresent); }
}
