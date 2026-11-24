package cn.jualn.miniapp.module.article.mapper;
import cn.jualn.miniapp.module.article.entity.ArticlePublisher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface ArticlePublisherMapper extends BaseMapper<ArticlePublisher> {
 ArticlePublisher lock(long id);
}
