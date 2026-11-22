package cn.jualn.miniapp.module.article;

import cn.jualn.miniapp.module.article.entity.Article;
import cn.jualn.miniapp.module.article.entity.ArticleCategory;
import cn.jualn.miniapp.module.article.mapper.ArticleCategoryMapper;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.article.mapper.ArticlePublisherMapper;
import cn.jualn.miniapp.module.article.service.impl.ArticleReferenceReadServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleReferenceOptionsTest {
    @Test void mapperBuildsBothTimeDirectionsAndIncludesColumnChildren() throws Exception {
        var configuration = new Configuration();
        try (var stream = Resources.getResourceAsStream("mapper/ArticleMapper.xml")) {
            new XMLMapperBuilder(stream, configuration, "mapper/ArticleMapper.xml", configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(ArticleMapper.class.getName() + ".referencePage");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("categoryId", 10L); parameters.put("q", "关键词"); parameters.put("beforeTime", LocalDateTime.now());
        parameters.put("beforeId", 20L); parameters.put("size", 21); parameters.put("ascending", true);
        var ascending = statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
        assertTrue(ascending.contains("published_at > ?"));
        assertTrue(ascending.contains("ORDER BY published_at ASC,id ASC"));
        assertTrue(ascending.contains("SELECT id FROM article_category WHERE parent_id=?"));
        parameters.put("ascending", false);
        var descending = statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
        assertTrue(descending.contains("published_at < ?"));
        assertTrue(descending.contains("ORDER BY published_at DESC,id DESC"));
    }
    @Test void categoryAndTimeOrderArePassedToOwningQueryAndBoundToCursor() {
        var articles = mock(ArticleMapper.class);
        var categories = mock(ArticleCategoryMapper.class);
        var category = new ArticleCategory();
        category.setId(10L); category.setName("主栏目"); category.setCreatedAt(LocalDateTime.now());
        when(categories.selectList(any())).thenReturn(List.of(category));
        var one = article(1L);
        var two = article(2L);
        when(articles.referencePage(eq(10L),any(),any(),anyInt(),any(),anyBoolean())).thenReturn(List.of(one,two));
        var service = new ArticleReferenceReadServiceImpl(articles, mock(ArticlePublisherMapper.class), categories);
        var result = service.options("关键词", null, 1, "actor-scope", 10L, "publishedAt");
        verify(articles).referencePage(10L,null,null,2,"关键词",true);
        assertEquals(10L,result.items().get(0).categoryId());
        assertEquals("主栏目",result.categoryOptions().get(0).displayName());
        assertNotNull(result.nextCursor());
        assertThrows(RuntimeException.class, () -> service.options("关键词",result.nextCursor(),1,"actor-scope",10L,"-publishedAt"));
        assertThrows(RuntimeException.class, () -> service.options("关键词",result.nextCursor(),1,"other-actor",10L,"publishedAt"));
    }

    private Article article(long id) {
        var article = new Article();
        article.setId(id); article.setTitle("材料" + id); article.setCategoryId(10L);
        article.setPublishedAt(LocalDateTime.of(2026,11,22,12,0));
        article.setOrganizationRepresentation(true); article.setPublisherNameSnapshot("发布方");
        return article;
    }
}
