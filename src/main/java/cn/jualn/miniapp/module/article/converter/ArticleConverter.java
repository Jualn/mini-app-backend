package cn.jualn.miniapp.module.article.converter;
import cn.jualn.miniapp.module.article.bo.*;
import cn.jualn.miniapp.module.article.vo.ArticleRepresentations.*;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import java.time.*;
import org.springframework.stereotype.Component;
@Component
public class ArticleConverter {
 /** Wire IDs remain opaque. A valid but unallocated spelling resolves to no resource, not a numeric binding error. */
 public static long lookupId(String value) {
  return lookupId(value,"path");
 }
 private static long lookupId(String value,String in) {
  if(value==null || value.isEmpty() || cn.jualn.miniapp.module.article.service.ArticleBodyCodec.length(value)>128)
   throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(in,"#","INVALID_VALUE","Invalid resource ID length"));
  try { return value.matches("[1-9][0-9]*")?Long.parseLong(value):0; }
  catch(NumberFormatException unallocated) { return 0; }
 }
 public static Long optionalLookupId(String value) { return value==null?null:lookupId(value,"query"); }
 public static Boolean optionalIncludeChildren(String value) {
  if(value==null) return null;
  if(!value.equals("true") && !value.equals("false")) cn.jualn.miniapp.module.article.service.ArticleBodyCodec.invalidQuery("#/includeChildren","Boolean true or false required");
  return Boolean.valueOf(value);
 }
 public static boolean deletedFilter(String value) {
  if(value==null || "false".equals(value)) return false;
  if("true".equals(value)) return true;
  cn.jualn.miniapp.module.article.service.ArticleBodyCodec.invalidQuery("#/deleted","Boolean true or false required");return false;
 }
 public static String id(Long id) { return id==null?null:id.toString(); }
 public static String time(LocalDateTime time) { return time==null?null:time.atOffset(ZoneOffset.ofHours(8)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
 public Category category(ArticleCategoryBO c) { return new Category(id(c.id()),id(c.parentId()),c.name(),c.displayOrder(),c.enabled(),time(c.createdAt()),time(c.updatedAt()),c.sceneCodes()); }
 public Publisher publisher(ArticlePublisherBO p) { return new Publisher(id(p.id()),p.displayName(),time(p.updatedAt()),operator(p.updatedBy())); }
 public Operator operator(Long id) { return new Operator("ADMIN",id(id)); }
 public Statistics statistics(ArticleStatisticsBO s) { return new Statistics(s.viewCount(),s.likeCount(),s.commentCount(),s.shareCount()); }
 public AdminDetail admin(ArticleBO a) { return new AdminDetail(id(a.id()),a.title(),a.summary(),id(a.categoryId()),a.publisherOrgId()==null?id(a.publisherId()):null,
  id(a.coverAttachmentId()),a.body(),a.commentsEnabled(),a.plainText(),a.publishStatus(),time(a.publishedAt()),time(a.lastPublishedAt()),time(a.createdAt()),time(a.updatedAt()),operator(a.createdBy()),operator(a.updatedBy()),a.publisherOrgId(),a.publisherNameSnapshot(),a.attachmentIds().stream().map(String::valueOf).toList(),a.source(),time(a.deletedAt()),a.deletedBy()==null?null:operator(a.deletedBy())); }
 public AdminSummary adminSummary(ArticleBO a) { return new AdminSummary(id(a.id()),a.title(),a.summary(),id(a.categoryId()),a.publisherOrgId()==null?id(a.publisherId()):null,
  id(a.coverAttachmentId()),a.commentsEnabled(),a.publishStatus(),time(a.publishedAt()),time(a.lastPublishedAt()),time(a.createdAt()),time(a.updatedAt()),a.publisherOrgId(),a.publisherNameSnapshot(),time(a.deletedAt()),a.deletedBy()==null?null:operator(a.deletedBy())); }
 public Summary summary(ArticleBO a) { return new Summary(id(a.id()),a.title(),a.summary(),cover(a),category(a.category()),
  a.publisherOrgId()==null?new PublicPublisher(id(a.publisherId()),a.publisher().displayName()):null,time(a.publishedAt()),a.commentsEnabled(),statistics(a.statistics()),a.publisherOrgId(),a.publisherNameSnapshot()); }
 public Detail detail(ArticleBO a) { return new Detail(id(a.id()),a.title(),a.summary(),cover(a),category(a.category()),
  a.publisherOrgId()==null?new PublicPublisher(id(a.publisherId()),a.publisher().displayName()):null,time(a.publishedAt()),a.commentsEnabled(),statistics(a.statistics()),
  a.body(),a.plainText(),a.media().stream().filter(m->java.util.Set.of("IMAGE","VIDEO").contains(m.getKind())).map(this::attachment).toList(),a.publisherOrgId(),a.publisherNameSnapshot(),a.attachmentIds().stream().map(id->a.media().stream().filter(m->id.equals(m.getId())).findFirst().map(this::attachment).orElseThrow(()->new IllegalStateException("Article document reference is missing"))).toList(),a.source()); }
 private AttachmentVO cover(ArticleBO a) { return a.coverAttachmentId()==null?null:a.media().stream().filter(m->a.coverAttachmentId().equals(m.getId())).findFirst().map(this::attachment).orElseThrow(()->new IllegalStateException("Article cover reference is missing")); }
 public AttachmentVO attachment(MediaAttachmentBO a) {
  return new cn.jualn.miniapp.module.media.converter.AttachmentConverter().toVO(a);
 }
}
