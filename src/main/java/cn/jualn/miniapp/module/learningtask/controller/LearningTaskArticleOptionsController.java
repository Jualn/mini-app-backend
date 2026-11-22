package cn.jualn.miniapp.module.learningtask.controller;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskArticleOptions;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
@RestController @RequiredArgsConstructor
public class LearningTaskArticleOptionsController {
 private final LearningTaskArticleOptions options;
 public record Publisher(String id,String displayName) {}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Item(String articleId,String title,String publishedAt,String publisherNameSnapshot,Publisher publisher,String categoryId) {}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Category(String id,String displayName,String parentId) {}
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Page(java.util.List<Item> items,String nextCursor,java.util.List<Category> categoryOptions) {}
 @GetMapping("/v1/admin/learning-task-article-options")
 public ResponseEntity<Page> list(@RequestParam(required=false) String taskId,@RequestParam(required=false) String targetType,@RequestParam(required=false) String organizationId,
    @RequestParam(required=false) String q,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String categoryId,@RequestParam(defaultValue="-publishedAt") String sort) {
  var value=options.list(ArticleConverter.optionalLookupId(taskId),targetType,organizationId,q,cursor,pageSize,ArticleConverter.optionalLookupId(categoryId),sort);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(value.items().stream().map(i->new Item(ArticleConverter.id(i.articleId()),i.title(),ArticleConverter.time(i.publishedAt()),i.publisherNameSnapshot(),i.publisher()==null?null:new Publisher(ArticleConverter.id(i.publisher().id()),i.publisher().displayName()),i.categoryId()==null?null:ArticleConverter.id(i.categoryId()))).toList(),value.nextCursor(),value.categoryOptions().stream().map(c->new Category(ArticleConverter.id(c.id()),c.displayName(),c.parentId()==null?null:ArticleConverter.id(c.parentId()))).toList()));
 }
}
