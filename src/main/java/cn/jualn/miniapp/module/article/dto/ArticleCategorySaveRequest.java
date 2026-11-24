package cn.jualn.miniapp.module.article.dto;
import cn.jualn.miniapp.module.article.bo.ArticleCategorySaveBO;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public record ArticleCategorySaveRequest(String name,Integer displayOrder,Boolean enabled,Long parentId, java.util.List<String> sceneCodes) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static ArticleCategorySaveRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("name","displayOrder","enabled","parentId","sceneCodes"),Set.of("name","displayOrder","enabled"));
  if(!value.get("displayOrder").isIntegralNumber() || !value.get("displayOrder").canConvertToInt() || !value.get("enabled").isBoolean()) ArticleBodyCodec.invalid("#","Invalid category fields");
  return new ArticleCategorySaveRequest(ArticleBodyCodec.text(value.get("name"),1,60,"#/name",true),value.get("displayOrder").intValue(),value.get("enabled").booleanValue(),value.has("parentId")?ArticleBodyCodec.id(value.get("parentId"),"#/parentId"):null, value.has("sceneCodes")?cn.jualn.miniapp.module.article.service.ArticleContentFields.scenes(value.get("sceneCodes")):null);
 }
 public ArticleCategorySaveBO command() { return new ArticleCategorySaveBO(name,displayOrder,enabled,parentId,sceneCodes); }
}
