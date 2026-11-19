package cn.jualn.miniapp.module.article;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.article.dto.ArticleSaveRequest;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ArticleBodyCodecTest {
 final ObjectMapper json=new ObjectMapper(); final ArticleBodyCodec codec=new ArticleBodyCodec();
 @Test void preservesSequenceAndExtractsSourceTextWithoutAltOrHref() throws Exception {
  var body=json.readTree("""
   {"format":"ARTICLE_RICH_TEXT","version":1,"nodes":[
    {"type":"HEADING","level":2,"runs":[{"text":"党建","marks":["BOLD"]}]},
    {"type":"PARAGRAPH","runs":[{"text":"学习","href":"https://example.org/read"},{"text":"纪要"}]},
    {"type":"LIST","ordered":false,"items":[{"runs":[{"text":"第一项"}]},{"runs":[{"text":"第二项"}]}]},
    {"type":"IMAGE","attachmentId":"12","alt":"不得重复进入阅读文本","caption":"照片说明"},
    {"type":"VIDEO","attachmentId":"13","posterAttachmentId":"12","caption":"视频说明"},{"type":"DIVIDER"}]}
   """);
  var document=codec.validate(body,12L);
  assertEquals(body,document.body()); assertEquals("党建\n学习纪要\n第一项\n第二项\n照片说明\n视频说明",document.plainText());
  assertEquals(java.util.Map.of(12L,"IMAGE",13L,"VIDEO"),document.references()); assertTrue(document.safetyText().contains("https://example.org/read"));
 }
 @Test void rejectsUnknownVersionNodesAttributesUnsafeLinksAndMixedMediaKinds() throws Exception {
  for(String node:new String[]{"{\"type\":\"HTML\"}","{\"type\":\"DIVIDER\",\"onclick\":\"x\"}",
   "{\"type\":\"PARAGRAPH\",\"runs\":[{\"text\":\"x\",\"href\":\"https://127.0.0.1/x\"}]}",
   "{\"type\":\"PARAGRAPH\",\"runs\":[{\"text\":\"x\",\"href\":\"javascript:alert(1)\"}]}",
   "{\"type\":\"VIDEO\",\"attachmentId\":\"12\"}"}) {
   var body=json.readTree("{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":1,\"nodes\":["+node+"]}");
   assertThrows(ContractProblemException.class,()->codec.validate(body,12L));
  }
  assertThrows(ContractProblemException.class,()->codec.validate(json.readTree("{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":2,\"nodes\":[]}"),null));
 }
 @Test void countsUnicodeCodePointsAndRejectsForgedServerFields() throws Exception {
  String emoji="😀".repeat(120); assertEquals(120,ArticleBodyCodec.length(emoji));
  var request=json.createObjectNode().put("title",emoji).put("summary","").putNull("categoryId").put("publisherId","1");
  request.set("body",json.readTree("{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":1,\"nodes\":[]}"));
  assertEquals(emoji,ArticleSaveRequest.parse(request).title());
  request.put("createdBy","99"); assertThrows(ContractProblemException.class,()->ArticleSaveRequest.parse(request));
 }
 @Test void cursorIsBoundToIdentityTargetThreadAndFilter() {
  String cursor=ArticleCursor.encode("2026-10-13T10:00:00|123","comments/4/8/null");
  assertEquals("2026-10-13T10:00:00|123",ArticleCursor.decode(cursor,"comments/4/8/null"));
  assertThrows(ContractProblemException.class,()->ArticleCursor.decode(cursor,"comments/5/8/null"));
  assertThrows(ContractProblemException.class,()->ArticleCursor.decode(cursor,"comments/4/9/null"));
  assertThrows(ContractProblemException.class,()->ArticleCursor.decode(cursor,"comments/4/8/77"));
  assertThrows(ContractProblemException.class,()->ArticleCursor.decode("!!!","public/null/-publishedAt"));
 }
 @Test void schemaWhitespaceAndUnicodeScalarValidationMatchTheWire() throws Exception {
  assertTrue(ArticleBodyCodec.blank("\u00a0\ufeff\u3000")); assertEquals("栏目",ArticleBodyCodec.trim("\u00a0栏目\u3000"));
  var body=json.readTree("{\"format\":\"ARTICLE_RICH_TEXT\",\"version\":1,\"nodes\":[{\"type\":\"PARAGRAPH\",\"runs\":[{\"text\":\"\\u00a0\"}]}]}");
  assertThrows(ContractProblemException.class,()->codec.validate(body,null));
  var lone=json.getNodeFactory().textNode("\ud800"); assertThrows(ContractProblemException.class,()->ArticleBodyCodec.text(lone,0,10,"#/title",false));
 }
}
