package cn.jualn.miniapp.module.article.dto;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import cn.jualn.miniapp.module.media.bo.ArticleUploadFileBO;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public record ArticleUploadRequest(List<ArticleUploadFileBO> files) {
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public static ArticleUploadRequest parse(JsonNode value) {
  ArticleBodyCodec.object(value,"#",Set.of("files"),Set.of("files"));
  if(!value.get("files").isArray() || value.get("files").size()<1 || value.get("files").size()>9) ArticleBodyCodec.invalid("#/files","Use 1..9 files");
  List<ArticleUploadFileBO> files=new ArrayList<>();
  for(var file:value.get("files")) {
   ArticleBodyCodec.object(file,"#/files",Set.of("fileName","kind","contentType","sizeBytes"),Set.of("fileName","kind","contentType","sizeBytes"));
   if(!file.get("sizeBytes").isIntegralNumber() || !file.get("sizeBytes").canConvertToLong()) ArticleBodyCodec.invalid("#/files/sizeBytes","Invalid byte size");
   files.add(new ArticleUploadFileBO(ArticleBodyCodec.text(file.get("fileName"),1,255,"#/files/fileName",true),
    ArticleBodyCodec.text(file.get("kind"),1,16,"#/files/kind",true),ArticleBodyCodec.text(file.get("contentType"),1,100,"#/files/contentType",true),file.get("sizeBytes").longValue()));
  }
  return new ArticleUploadRequest(List.copyOf(files));
 }
}
