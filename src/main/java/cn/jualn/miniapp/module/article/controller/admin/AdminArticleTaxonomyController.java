package cn.jualn.miniapp.module.article.controller.admin;
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
public class AdminArticleTaxonomyController {
 private final ArticleService service;
 private final ArticleConverter converter;
 @GetMapping("/v1/admin/article-categories") public ResponseEntity<Categories> categories() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Categories(service.categories(true).stream().map(converter::category).toList())); }
 @GetMapping("/v1/admin/article-categories/{categoryId}") public ResponseEntity<Category> category(@PathVariable String categoryId) {
  var c=service.category(ArticleConverter.lookupId(categoryId)); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("article-category",c.id(),c.revision())).body(converter.category(c));
 }
 @PostMapping("/v1/admin/article-categories") public ResponseEntity<Category> createCategory(@RequestBody ArticleCategorySaveRequest request) {
  var c=service.createCategory(request.command()); return ResponseEntity.created(URI.create("/v1/admin/article-categories/"+c.id())).cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("article-category",c.id(),c.revision())).body(converter.category(c));
 }
 @PutMapping("/v1/admin/article-categories/{categoryId}") public ResponseEntity<Category> replaceCategory(@PathVariable String categoryId,
  @RequestHeader(value="If-Match",required=false) String ifMatch,@RequestBody ArticleCategorySaveRequest request) {
  var c=service.replaceCategory(ArticleConverter.lookupId(categoryId),request.command(),ifMatch); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("article-category",c.id(),c.revision())).body(converter.category(c));
 }
 @GetMapping("/v1/admin/article-publishers") public ResponseEntity<Publishers> publishers() {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Publishers(service.publishers().stream().map(converter::publisher).toList()));
 }
 @GetMapping("/v1/admin/article-publishers/{publisherId}") public ResponseEntity<Publisher> publisher(@PathVariable String publisherId) {
  var p=service.publisher(ArticleConverter.lookupId(publisherId)); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(StrongEtag.of("article-publisher",p.id(),p.revision())).body(converter.publisher(p));
 }
}
