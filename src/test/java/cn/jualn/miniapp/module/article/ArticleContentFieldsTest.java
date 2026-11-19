package cn.jualn.miniapp.module.article;
import cn.jualn.miniapp.module.article.dto.*;
import cn.jualn.miniapp.module.article.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ArticleContentFieldsTest {
 final ObjectMapper json=new ObjectMapper();
 String base="{\"title\":\"T\",\"summary\":\"\",\"categoryId\":null,\"publisherOrgId\":\"org\",\"body\":{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":1,\"nodes\":[]}}";
 @Test void omissionClearingAndInvalidNullStayDistinct() throws Exception {
  var value=json.readTree(base);var old=ArticleSaveRequest.parse(value).command();assertNull(old.attachmentIds());assertFalse(old.sourcePresent());
  ((com.fasterxml.jackson.databind.node.ObjectNode)value).putArray("attachmentIds");((com.fasterxml.jackson.databind.node.ObjectNode)value).putNull("source");
  var clear=ArticleSaveRequest.parse(value).command();assertTrue(clear.attachmentIds().isEmpty());assertTrue(clear.sourcePresent());assertNull(clear.source());
  ((com.fasterxml.jackson.databind.node.ObjectNode)value).putNull("attachmentIds");assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->ArticleSaveRequest.parse(value));
 }
 @Test void scenesSourceLinksAndFilesAreClosedVocabulary() throws Exception {
  for(String value:java.util.List.of("[\"UNKNOWN\"]","[\"E_SAFETY\",\"E_SAFETY\"]","null","{}")) assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->ArticleContentFields.scenes(json.readTree(value)));
  for(String url:java.util.List.of("http://example.org/a","https://127.0.0.1/a","https://example.org:8080/a","https://user@example.org/a")) assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->ArticleContentFields.source(json.createObjectNode().put("name","Source").put("url",url)));
  assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->ArticleContentFields.files(json.readTree("[\"1\",\"1\"]")));
 }
}
