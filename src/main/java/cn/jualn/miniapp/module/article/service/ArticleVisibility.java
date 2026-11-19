package cn.jualn.miniapp.module.article.service;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
/** Live visibility has no positive existence cache: unpublish must affect the next API operation. */
@Service @RequiredArgsConstructor
public class ArticleVisibility {
 private final ArticleMapper mapper;
 public boolean visible(long id) { return mapper.visible(id); }
 public boolean exists(long id) { return mapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<cn.jualn.miniapp.module.article.entity.Article>().eq(cn.jualn.miniapp.module.article.entity.Article::getId,id))>0; }
}
