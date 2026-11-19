package cn.jualn.miniapp.module.article.controller.admin;

import cn.jualn.miniapp.module.article.service.ArticleOrganizationPolicy;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ArticleOrganizationController {
 private final ArticleOrganizationPolicy organizations;
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Page(List<ArticleOrganizationPolicy.Candidate> items,String nextCursor) {}
 @GetMapping("/v1/admin/article-publisher-organizations")
 public ResponseEntity<Page> candidates(@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor) {
  var page=organizations.candidates(pageSize,cursor);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(page.items(),page.nextCursor()));
 }
}
