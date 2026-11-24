package cn.jualn.miniapp.module.article.controller;

import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;
import cn.jualn.miniapp.module.article.vo.ArticleRepresentations.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
public class ArticleController {
    private final ArticleService service;
    private final ArticleConverter converter;

    @GetMapping("/v1/articles")
    public ResponseEntity<Page> list(@RequestParam(required = false) String categoryId,
                                     @RequestParam(required = false) String includeChildren,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "20") int pageSize,
                                     @RequestParam(defaultValue = "-publishedAt") String sort,
                                     @RequestParam(required=false) String q,@RequestParam(required=false) String sceneCode) {
        var page = service.listPublic(ArticleConverter.optionalLookupId(categoryId), ArticleConverter.optionalIncludeChildren(includeChildren), cursor, pageSize, sort,q,sceneCode);
        return response(new Page(page.items().stream().map(converter::summary).toList(), page.nextCursor()));
    }

    @GetMapping("/v1/articles/{articleId}")
    public ResponseEntity<Detail> get(@PathVariable String articleId) {
        return response(converter.detail(service.getPublic(ArticleConverter.lookupId(articleId))));
    }

    @GetMapping("/v1/article-categories")
    public ResponseEntity<Categories> categories() {
        return response(new Categories(service.categories(false).stream().map(converter::category).toList()));
    }

    private <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
