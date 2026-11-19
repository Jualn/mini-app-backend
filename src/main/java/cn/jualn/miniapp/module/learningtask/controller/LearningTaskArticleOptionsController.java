package cn.jualn.miniapp.module.learningtask.controller;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleOptions;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequiredArgsConstructor
public class LearningTaskArticleOptionsController {
 private final LearningTaskArticleOptions options;
 public record Publisher(String id,String displayName) {}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Item(String articleId,String title,String publishedAt,String publisherNameSnapshot,Publisher publisher) {}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Page(java.util.List<Item> items,String nextCursor) {}
 @GetMapping("/v1/admin/learning-task-article-options")
 public ResponseEntity<Page> list(@RequestParam(required=false) String taskId,@RequestParam(required=false) String targetType,@RequestParam(required=false) String organizationId,
    @RequestParam(required=false) String q,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize) {
  var value=options.list(ArticleConverter.optionalLookupId(taskId),targetType,organizationId,q,cursor,pageSize);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(value.items().stream().map(i->new Item(ArticleConverter.id(i.articleId()),i.title(),ArticleConverter.time(i.publishedAt()),i.publisherNameSnapshot(),i.publisher()==null?null:new Publisher(ArticleConverter.id(i.publisher().id()),i.publisher().displayName()))).toList(),value.nextCursor()));
 }
}
