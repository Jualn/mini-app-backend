package cn.jualn.miniapp.module.article.controller.admin;
import cn.jualn.miniapp.module.article.bo.ArticleBO;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.dto.*;
import cn.jualn.miniapp.module.article.vo.ArticleRepresentations.*;
import cn.jualn.miniapp.common.web.StrongEtag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/v1/admin/articles")
public class AdminArticleController {
 private final ArticleService service;
 private final ArticleConverter converter;
 @GetMapping public ResponseEntity<AdminPage> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
  @RequestParam(required=false) String publishStatus,@RequestParam(required=false) String categoryId,@RequestParam(required=false) String includeChildren,@RequestParam(required=false) String publisherId,@RequestParam(required=false) String publisherOrgId,
  @RequestParam(required=false) String q,@RequestParam(defaultValue="-updatedAt") String sort,@RequestParam(required=false) String deleted) {
  var value=service.listAdmin(page,pageSize,publishStatus,ArticleConverter.optionalLookupId(categoryId),ArticleConverter.optionalIncludeChildren(includeChildren),ArticleConverter.optionalLookupId(publisherId),publisherOrgId,q,sort,ArticleConverter.deletedFilter(deleted));
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AdminPage(value.items().stream().map(converter::adminSummary).toList(),value.page(),value.pageSize(),value.totalItems()));
 }
 @GetMapping("/{articleId}") public ResponseEntity<AdminDetail> get(@PathVariable String articleId) { return response(service.getAdmin(ArticleConverter.lookupId(articleId)),false); }
 @PostMapping public ResponseEntity<AdminDetail> create(@RequestBody ArticleSaveRequest request) { return response(service.create(request.command()),true); }
 @PutMapping("/{articleId}") public ResponseEntity<AdminDetail> replace(@PathVariable String articleId,
  @RequestHeader(value="If-Match",required=false) String ifMatch,@RequestBody ArticleSaveRequest request) { return response(service.replace(ArticleConverter.lookupId(articleId),request.command(),ifMatch),false); }
 @PostMapping("/{articleId}:publish") public ResponseEntity<AdminDetail> publish(@PathVariable String articleId,
  @RequestHeader(value="If-Match",required=false) String ifMatch) { return response(service.publish(ArticleConverter.lookupId(articleId),ifMatch),false); }
 @PostMapping("/{articleId}:unpublish") public ResponseEntity<AdminDetail> unpublish(@PathVariable String articleId,
  @RequestHeader(value="If-Match",required=false) String ifMatch,@RequestBody(required=false) ArticleUnpublishRequest body) { return response(service.unpublish(ArticleConverter.lookupId(articleId),ifMatch,body==null?"NORMAL":body.mode(),body==null?null:body.reason()),false); }
 @DeleteMapping("/{articleId}") public ResponseEntity<Void> delete(@PathVariable String articleId,@RequestHeader(value="If-Match",required=false) String tag) {
  service.delete(ArticleConverter.lookupId(articleId),tag);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
 @PostMapping("/{articleId}:restore") public ResponseEntity<AdminDetail> restore(@PathVariable String articleId,@RequestHeader(value="If-Match",required=false) String tag) {return response(service.restore(ArticleConverter.lookupId(articleId),tag),false);}
 @GetMapping("/{articleId}/learning-task-usage") public ResponseEntity<ArticleService.Usage> usage(@PathVariable String articleId) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.learningTaskUsage(ArticleConverter.lookupId(articleId)));}
 private ResponseEntity<AdminDetail> response(ArticleBO a,boolean created) {
  var builder=created?ResponseEntity.created(URI.create("/v1/admin/articles/"+a.id())):ResponseEntity.ok();
  return builder.cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("article",a.id(),a.revision())).body(converter.admin(a));
 }
}
