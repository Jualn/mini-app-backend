package cn.jualn.miniapp.module.article.mapper;
import cn.jualn.miniapp.module.article.entity.ArticleCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface ArticleCategoryMapper extends BaseMapper<ArticleCategory> {
 ArticleCategory lock(long id);
}
