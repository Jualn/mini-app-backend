package cn.jualn.miniapp.module.article.controller;
import cn.jualn.miniapp.module.article.service.ArticleFavorites;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.vo.ArticleRepresentations.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.List;
@RestController @RequiredArgsConstructor
public class ArticleFavoriteController {
 private final ArticleFavorites service;private final ArticleConverter converter;
 @JsonInclude(JsonInclude.Include.NON_NULL) public record Item(String articleId,String favoritedAt,String availability,Summary article) {}
 @JsonInclude(JsonInclude.Include.NON_NULL) public record Page(List<Item> items,String nextCursor) {}
 @GetMapping("/v1/articles/{articleId}/favorite") public ResponseEntity<ArticleFavorites.State> get(@PathVariable String articleId) {return response(service.get(ArticleConverter.lookupId(articleId)));}
 @PutMapping("/v1/articles/{articleId}/favorite") public ResponseEntity<ArticleFavorites.State> set(@PathVariable String articleId) {return response(service.set(ArticleConverter.lookupId(articleId)));}
 @DeleteMapping("/v1/articles/{articleId}/favorite") public ResponseEntity<ArticleFavorites.State> remove(@PathVariable String articleId) {service.remove(ArticleConverter.lookupId(articleId));return response(new ArticleFavorites.State(articleId,false));}
 @GetMapping("/v1/users/me/article-favorites") public ResponseEntity<Page> list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize) {
  var page=service.list(cursor,pageSize);
  return response(new Page(page.items().stream().map(i->new Item(Long.toString(i.articleId()),ArticleConverter.time(i.favoritedAt()),i.article()==null?"UNAVAILABLE":"AVAILABLE",i.article()==null?null:converter.summary(i.article()))).toList(),page.nextCursor()));
 }
 private <T> ResponseEntity<T> response(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
